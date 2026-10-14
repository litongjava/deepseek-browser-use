---
name: douyin-research-and-outreach
description: 使用 dsb 在抖音网页版检索视频、阅读虚拟列表评论并研究用户需求；用户明确授权私信时可定向联系，包含富文本输入和平台消息限制。
---

> Claude Code：`dsb-skills:douyin-research-and-outreach`。

# 抖音需求调研与定向私信实操手册

通用命令、等待、人工协助和任务收尾见 [主技能](../deepseek-browser-use/SKILL.md)。

仅在用户明确授权后发送私信；单纯调研不包含联系他人。

## 一、 站点核心特征与关键架构

| 维度 | 站点特征与运行机制 |
| :--- | :--- |
| **入口站点** | 主站 `https://www.douyin.com` 或 抖音精选 `https://www.douyin.com/jingxuan`（精选版布局更干净） |
| **前端架构** | React SPA + 虚拟列表（Virtual List） + 自定义富文本编辑器（editor-kit） |
| **登录态** | 托管在 `profile` 目录中。首次扫码登录后持久化，后续无需重复扫码 |
| **安全机制** | 频繁跨域检索可能触发滑动验证码（由 `rmc.bytedance.com` 承载）；私信受平台单向陌生人保护 |

---

## 二、 避坑指南（七大高频实测踩坑与解决方案）

### 坑 1：视频自动播放导致截图 8000ms 超时并触发熔断（CAPTURE_FAILED / circuit-open）
- **现象**：打开视频播放页或含视频卡片的搜索页时，Playwright 等待渲染静止超时 8 秒，报错 `CAPTURE_FAILED`，随后所有命令提示 `capture: stage: circuit-open`（截图熔断）。
- **避坑规范**：
  1. **禁止依赖图片截图进行页面判断**，一律走 `dsb state --text-only` 读取结构化文本，或通过 `dsb js` 直接抓取 DOM；
  2. 熔断是针对截图的自我保护，**底层的浏览器操作、导航和 JS 执行完全不受影响**；
  3. 不需要视觉留档时，直接忽略熔断日志，继续执行业务操作。

### 坑 2：评论“假加载”——仅修改 scrollTop 无法渲染新评论
- **现象**：很多外层 `<div>`（如 `.comment-mainContent`）看似有高度，但对其 `scrollTop` 赋值后，评论数始终定死在 15 条，无法加载新数据。
- **根本原因**：抖音采用 React 虚拟列表，滚动监听挂载在顶层容器 `.route-scroll-container` 上，并且必须派发标准的浏览器滚动事件才能触发 React 的状态刷新。
- 写法见步骤 3 的脚本：更新容器 scrollTop 后派发 scroll 事件。虚拟列表只保留当前渲染项，需要累计时逐轮提取并去重。

### 坑 3：动态刷新导致快照索引瞬间失效（STALE_ELEMENT）
- **现象**：刚调完 `get_browser_state` 拿到索引 86，紧接着调用 `click_element_by_index -p index=86`，立即报错 `索引越界/当前没有页面快照`。
- **原因**：视频帧、弹幕和推荐流实时重绘，DOM 树在百毫秒内被重构。
- **解决方案**：
  - 优先用 JS 获取元素的实际屏幕坐标（`getBoundingClientRect()`），然后用 `dsb run mouse_click -p x=.. -p y=..` 点击；
  - 或者直接在 JS 内执行 `el.click()`。

### 坑 4：SPURIOUS_DISPATCH

只读脚本可用 `--retry-on-spurious`。滚动、输入或发送中报错时先回读状态，不自动重发；重发滚动脚本可能跳过评论。

### 坑 5：私信输入框是 contenteditable 富文本编辑器，传统输入命令无效
- **现象**：私信窗口的输入区是一个类名为 `.messageEditorinputArea` 的 `<div contenteditable="true">`，不是原生 `<input>` 或 `<textarea>`，调用 `input_text` 无法触发组件的 React 模型。
- **标准输入与发送三步法**：
  1. 获得焦点：`editor.focus()`；
  2. 注入文本：使用系统底层文本插入命令：
     ```javascript
     document.execCommand('insertText', false, "你好，我是独立开发者...");
     ```
  3. 发送消息：使用 dsb 的按键命令触发回车：
     ```bash
     dsb --id <ID> run send_keys -p keys=Enter
     # 或使用已支持的别名
     dsb --id <ID> run press_key -p key=Enter
     ```

### 坑 6：平台隐私设置导致对方“无法回复私信”
- **现象**：私信发出后，聊天框内出现灰色系统提示：*“由于你的隐私设置，对方无法回复你的私信。你可以在 抖音App 修改状态”*。
- **应对方案**：首次执行私信触达前，提醒用户在手机端抖音打开：【我】➔【设置】➔【隐私设置】➔【私信设置】➔ 允许“所有人”发私信，否则潜在客户将无法回复你的需求确认。

### 坑 7：陌生人单向触达规则与防封限制
- **规则**：抖音对陌生人私信有严格的风控：在对方回复或关注你之前，**只能发送 1 条文本消息**，且每天向陌生人发信有频次上限。
- **最佳实践**：首次私信文案必须高度凝练、直奔主题，说明身份、来意、看到了哪条具体需求、以及询问付费意愿，切忌分多条发送。

---

## 三、 标准执行流水线（复制即可运行）

### 步骤 1：启动带头浏览器
```bash
dsb --id 2026100901 start --browser chrome --headful
```

### 步骤 2：导航至目标视频或搜索页
```bash
dsb --id 2026100901 run go_to_url -p url='https://www.douyin.com/video/<视频ID>'
```

### 步骤 3：唤起评论区并穿透虚拟列表加载深度评论
```bash
# 1. 点击评论图标（通过 JS 找到评论按钮并点击）
dsb --id 2026100901 js "() => { const b = document.querySelector('[data-e2e="comment-icon"]') || Array.from(document.querySelectorAll('*')).find(e => e.getAttribute('aria-label')?.includes('评论')); b?.click(); }"

# 2. 滚动后提取当前渲染的评论，不保证覆盖全部评论
dsb --id 2026100901 js '@scroll_and_extract.js' --out comments.json
```

`scroll_and_extract.js` 脚本模板：
```javascript
async () => {
  const container = document.querySelector('.route-scroll-container');
  if (container) {
    for (let i = 0; i < 8; i++) {
      container.scrollTop += 1200;
      container.dispatchEvent(new Event('scroll', { bubbles: true }));
      await new Promise(r => setTimeout(r, 800));
    }
  }

  const items = Array.from(document.querySelectorAll('[data-e2e="comment-item"]'));
  return items.map(item => {
    const a = item.querySelector('a[href*="/user/"]');
    return {
      userName: a?.innerText || item.querySelector('[data-e2e="comment-user-name"]')?.innerText,
      userLink: a?.href,
      text: item.innerText
    };
  }).filter(i => i.userLink && i.text.length > 20);
}
```

### 步骤 4：进入目标用户主页并发送私信确认需求
```bash
# 1. 跳转到用户主页
dsb --id 2026100901 run go_to_url -p url='<用户主页URL>'

# 2. 唤起私信窗口并注入文本
dsb --id 2026100901 js '@send_dm.js'

# 3. 敲回车完成发送
dsb --id 2026100901 run send_keys -p keys=Enter
```

`send_dm.js` 脚本模板：
```javascript
async () => {
  const btn = Array.from(document.querySelectorAll('button, div, span')).find(el => el.textContent?.trim() === '私信' && el.getBoundingClientRect().width > 0);
  if (!btn) return { error: "未找到私信按钮" };
  btn.click();
  await new Promise(r => setTimeout(r, 1500));

  const editor = document.querySelector('.messageEditorinputArea') || document.querySelector('[contenteditable="true"]');
  if (!editor) return { error: "未找到私信输入框" };

  editor.focus();
  const msg = "<用户已确认的身份、来意和需求问题>";
  document.execCommand('insertText', false, msg);
  return { ok: true, preview: editor.innerText };
}
```
