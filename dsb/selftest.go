package main

// 端到端自检:对当前服务跑一遍,连不上、参数不对、新命令没生效都会在这里暴露。
//
// 与 Python 版逐项对应:健康检查、命令清单里新命令是否都在、start 的 engineHonored、
// 打开本地自检页、wait_for_count、get_modals/close_modal、点击降级到真实鼠标、
// expect 断言能报出不一致、异步批次与 get_job、任务清单/配置/配方可读、cleanup 默认只预演。
// 自检页面是本地生成的 HTML,**不依赖外网**。

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// selftestTaskID 特意避开业务常用的小号(1001/2002…),自检可以随便跑、随便关。
const selftestTaskID = 990001

const selftestPage = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>dsb selftest</title>
<style>
  @keyframes slide { from { transform: translateX(0); } to { transform: translateX(60px); } }
  #moving { display: inline-block; animation: slide 1.6s linear infinite alternate; }
  .ant-modal-wrap { position: fixed; left: 300px; top: 300px; width: 420px; height: 220px; z-index: 1000; }
</style></head><body>
  <div id="moving">一直在动的按钮</div>
  <div id="clickFlag">idle</div>
  <div id="rows"></div>
  <div class="ant-modal-wrap" id="modalWrap">
    <div class="ant-modal" role="dialog">
      <div class="ant-modal-content">
        <div class="ant-modal-confirm-title">确认提交申请？</div>
        <div class="ant-modal-confirm-btns">
          <button class="ant-btn" id="mCancel">取消</button>
          <button class="ant-btn ant-btn-primary" id="mOk">确定</button>
        </div>
      </div>
      <button class="ant-modal-close" id="mClose">×</button>
    </div>
  </div>
  <script>
    document.getElementById('moving').addEventListener('click', function () {
      document.getElementById('clickFlag').textContent = 'moving-clicked';
    });
    document.getElementById('mCancel').addEventListener('click', function () {
      document.getElementById('modalWrap').remove();
    });
    setTimeout(function () {
      var box = document.getElementById('rows');
      for (var i = 0; i < 3; i++) { box.insertAdjacentHTML('beforeend', '<div class="row">r' + i + '</div>'); }
    }, 400);
  </script>
</body></html>
`

// cmdSelftest 对当前服务跑一遍端到端自检。
func cmdSelftest(client *Client, args *Args, out *Printer) (int, error) {
	failures := []string{}
	check := func(name string, condition bool, detail string) {
		mark := "失败"
		if condition {
			mark = "通过"
		} else {
			failures = append(failures, name)
		}
		suffix := ""
		if detail != "" {
			suffix = "  " + detail
		}
		out.Line(fmt.Sprintf("  [%s] %s%s", mark, name, suffix))
	}

	// 自检要能反复跑,所以用自己的任务 id(默认 990001,不会撞上业务任务),并先清掉上一轮的残留
	if args.ID != nil {
		parsed, err := jsonIntValue(*args.ID)
		if err != nil {
			return 0, err
		}
		client.TaskID = parsed
	} else if env := os.Getenv("DSB_TASK_ID"); env != "" {
		parsed, err := jsonIntValue(env)
		if err != nil {
			return 0, err
		}
		client.TaskID = parsed
	} else {
		client.TaskID = selftestTaskID
	}
	recordNote := client.Session
	if recordNote == "" {
		recordNote = "未记录"
	}
	out.Line(fmt.Sprintf("自检目标:%s(任务 id=%d,会话=%s)", client.BaseURL, client.TaskID, recordNote))

	health, err := client.Health()
	if err != nil {
		out.Warn("ERROR " + err.Error())
		return ExitTransport, nil
	}
	check("健康检查", health.Ok(), health.Msg())

	methods, err := client.Methods()
	if err != nil {
		return 0, err
	}
	names := map[string]bool{}
	if methods.Ok() {
		for _, item := range asArray(objGet(methods.Data(), "methods")) {
			if text, ok := item.(string); ok {
				names[text] = true
			}
		}
	}
	check("命令清单", len(names) > 0, fmt.Sprintf("%d 个方法", len(names)))
	for _, needed := range []string{"get_modals", "close_modal", "mouse_click", "wait_for_stable", "wait_for_count",
		"get_config", "list_tasks", "run_recipe", "get_job", "cleanup"} {
		check("新命令可用:"+needed, names[needed], "")
	}

	// 上一轮自检可能留下同名实例,先清掉(不存在时失败也无所谓)
	if _, err := client.Close(nil); err != nil {
		return 0, err
	}
	started, err := client.Start(derefOr(args.Browser, ""), true, nil)
	if err != nil {
		return 0, err
	}
	check("启动任务", started.Ok(), started.Msg())
	if !started.Ok() {
		return ExitBusiness, nil
	}
	defer func() {
		closed, closeErr := client.Close(nil)
		if closeErr != nil {
			check("关掉任务", false, closeErr.Error())
			return
		}
		check("关掉任务", closed.Ok(), closed.Msg())
	}()

	honored, hasHonored := objBool(started.Data(), "engineHonored")
	check("引擎参数被采纳", !hasHonored || honored,
		fmt.Sprintf("请求=%s 实际=%s", objStr(started.Data(), "requestedBrowser"),
			objStr(started.Data(), "effectiveBrowser")))

	// 自检页面用本地文件,不依赖外网
	fixture := filepath.Join(client.RecordDir, "selftest-fixture.html")
	if err := writeTextFile(fixture, selftestPage); err != nil {
		return 0, err
	}
	absolute, err := filepath.Abs(fixture)
	if err != nil {
		return 0, err
	}
	url := fileURL(absolute)

	page, err := client.Command("go_to_url", ObjOf("url", url), nil, 0, "go_to_url")
	if err != nil {
		return 0, err
	}
	check("打开自检页面", page.Ok(), page.Msg())

	title, err := client.Command("get_title", NewObj(), nil, 0, "get_title")
	if err != nil {
		return 0, err
	}
	check("读标题", title.Ok() && strings.Contains(objStr(title.Data(), "title"), "selftest"),
		objStr(title.Data(), "title"))

	batch, err := client.Batch([]Value{
		ObjOf("wait_for_count", ObjOf("selector", ".row", "min", numberFromInt(3), "timeoutSeconds", numberFromInt(10))),
		ObjOf("get_modals", NewObj()),
		ObjOf("close_modal", ObjOf("which", "top", "button", "取消")),
		ObjOf("get_modals", NewObj()),
		ObjOf("click_element_by_selector", ObjOf("selector", "#moving", "mode", "auto", "timeoutMs", numberFromInt(900))),
	}, false, false, false, 0, 0, nil)
	if err != nil {
		return 0, err
	}
	results := asArray(objGet(batch.Data(), "results"))
	check("批量执行", batch.Ok() && len(results) == 5, batch.Msg())

	stepData := func(position int) Value {
		// 按位置取某一步的 data(同名的命令可能出现多次,按名字取会拿错)
		if position < len(results) {
			return objGet(results[position], "data")
		}
		return nil
	}
	check("wait_for_count 等到 3 行", pyStr(objGet(stepData(0), "count")) == "3", "")
	check("get_modals 看到 1 个弹窗", pyStr(objGet(stepData(1), "count")) == "1", "")
	closed, _ := objGet(stepData(2), "closed").(bool)
	check("close_modal 真的关掉了", closed, "")
	check("关掉之后弹窗归零", pyStr(objGet(stepData(3), "count")) == "0", "")
	check("点击降级到真实鼠标", objStr(stepData(4), "mode") == "mouse", "mode="+objStr(stepData(4), "mode"))

	// 断言:故意让 expect 不通过,验证「动作成功但状态没变」会被标出来
	expect, err := client.Batch([]Value{
		ObjOf("execute_js", ObjOf("body", "() => 1"), "expect",
			ObjOf("js", "() => 2", "equals", numberFromInt(3))),
	}, false, false, false, 0, 0, nil)
	if err != nil {
		return 0, err
	}
	check("expect 断言能报出不一致",
		!expect.Ok() && pyStr(objGet(expect.Data(), "expectFailed")) == "1" &&
			pyStr(objGet(expect.Data(), "failed")) == "0", expect.Msg())

	// 异步批次
	asyncStart, err := client.Batch([]Value{ObjOf("execute_js", ObjOf("body", "() => 42"))},
		false, false, true, 0, 0, nil)
	if err != nil {
		return 0, err
	}
	jobID := objStr(asyncStart.Data(), "jobId")
	check("异步批次返回 jobId", jobID != "", jobID)
	if jobID != "" {
		final, waitErr := client.WaitJob(jobID, 0.5, 30, nil)
		if waitErr != nil {
			check("异步任务跑完", false, waitErr.Error())
		} else {
			check("异步任务跑完", objStr(final.Data(), "status") == "done", objStr(final.Data(), "status"))
		}
	}

	tasks, err := client.Tasks()
	if err != nil {
		return 0, err
	}
	sawSelf := false
	for _, item := range asArray(objGet(tasks.Data(), "tasks")) {
		if pyStr(objGet(item, "id")) == pyStr(numberFromInt(client.TaskID)) {
			sawSelf = true
		}
	}
	check("任务清单能看到自己", tasks.Ok() && sawSelf, "")

	config, err := client.Config()
	if err != nil {
		return 0, err
	}
	check("生效配置可读", config.Ok() && objStr(config.Data(), "engine") != "",
		fmt.Sprintf("引擎=%s profile=%s", objStr(config.Data(), "engine"),
			objStr(objGet(config.Data(), "profileDir"), "resolved")))

	recipes, err := client.Command("list_recipes", NewObj(), nil, 0, "list_recipes")
	if err != nil {
		return 0, err
	}
	check("配方库可读", recipes.Ok(), pyStr(objGet(recipes.Data(), "count"))+" 个配方")

	cleanup, err := client.Command("cleanup",
		ObjOf("scope", "data", "olderThanHours", numberFromInt(24)), nil, 0, "cleanup")
	if err != nil {
		return 0, err
	}
	dryRun, _ := objGet(cleanup.Data(), "dryRun").(bool)
	check("cleanup 默认只预演", cleanup.Ok() && dryRun, "")

	if len(failures) > 0 {
		out.Line(fmt.Sprintf("自检结果:%d 项失败 -> %s", len(failures), strings.Join(failures, ", ")))
		return ExitBusiness, nil
	}
	out.Line("自检结果:全部通过")
	return ExitOK, nil
}

// fileURL 把绝对路径变成 file:// URL。
func fileURL(absolute string) string {
	slashed := filepath.ToSlash(absolute)
	if !strings.HasPrefix(slashed, "/") {
		slashed = "/" + slashed
	}
	return "file://" + encodeURLPath(slashed)
}

// encodeURLPath 只转义空格与几个会破坏 URL 的字符(路径里通常只有这些)。
func encodeURLPath(path string) string {
	replacer := strings.NewReplacer(
		" ", "%20", "#", "%23", "?", "%3F", "%", "%25", "[", "%5B", "]", "%5D")
	return replacer.Replace(path)
}

// jsonIntValue 把字符串解析成整数(自检的 --id 与 DSB_TASK_ID 共用)。
func jsonIntValue(text string) (int, error) {
	parsed, err := jsonNumberInt(text)
	if err != nil {
		return 0, usageErrorf("任务 id 必须是数字(例如 1001 或雪花 ID),收到的是:%s", pyRepr(text))
	}
	return parsed, nil
}
