---
name: wecom-register-certify
description: 使用 dsb 注册或认证企业微信，填写主体信息、上传营业执照并查询审核结果；包含组件上传排障，法人扫脸、短信验证、最终确认和支付交由用户完成。
---

> Claude Code：`dsb-skills:wecom-register-certify`。

# 企业微信：注册 + 企业认证 + 支付 ¥300 + 查审核（实操手册）

一句话流程：**有头开浏览器 → 人登录 → 进认证表单 → 逐步填写 → 上传营业执照（组件直调）→ 交人扫脸/短信/支付 → 查审核结果**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录，不是企业微信的产品说明书。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这个后台里「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：把认证表单填到「确认并提交」之前即停。**提交、扫脸、短信码、支付由人做**，智能体不要代点支付。
- **必须由人做的四件事**（智能体一律做不到，别硬试）：
  1. **法人扫脸**：弹出二维码，必须由**法定代表人本人**用微信扫码并完成人脸识别；
  2. **短信验证码**：发到**管理员手机号**上，智能体读不到那条短信；
  3. **支付 ¥300**：微信支付扫码/确认，涉及资金，只能由人操作；
  4. **最终确认**：提交前的确认对话框，属于法律性质的申报确认。
- **不做的事**：不绕过人脸识别、不伪造或美化营业执照、不用不实的工商信息、不代付、不代替法人签署任何承诺。
- **要提醒用户的事**：
  - 认证提交的是**工商登记信息 + 法人身份信息**，必须与营业执照完全一致（企业全称、18 位统一社会信用代码一个字都不能错）；
  - 认证费 ¥300 **生效一年**，到期要续；
  - 认证审核由**第三方审核机构**执行（具体是哪一家因订单而异，订单详情页的「审核机构」一栏会写明，
    并附咨询电话 / 邮箱 / 在线客服微信号 / 咨询时间），企业微信只是通道；审核中可能被退回补充材料。

## 1. 开工前

### 1.1 服务与浏览器

通用命令、等待、人工协助和任务收尾见 [主技能](../deepseek-browser-use/SKILL.md)。 使用有头 Chrome，扫码期间保留任务，避免中途切换 profile。

### 1.2 开工前必须问清的字段（一次问全，别填到一半停下来）

| 组 | 字段 | 说明 |
| --- | --- | --- |
| 主体 | `<企业全称>`、`<18 位统一社会信用代码>` | 必须与营业执照逐字一致；信用代码 18 位 |
| 法人 | `<法人姓名>`、`<法人身份证号>` | 扫脸必须是**这个人本人** |
| 管理员 | `<管理员手机号>`、`<管理员邮箱>` | 手机号用来收短信码；邮箱用于接收通知 |
| 材料 | **营业执照扫描件/照片** | 见第 4 节，上传是整个流程最容易翻车的一步 |
| 可选 | 企业简称（如 `<企业简称>`）、企业 logo、企业地址、联系电话 | 简称有命名规则，见 3.4 |

> **先确认「企业是否已经存在」再动手**：企业微信一个手机号/微信号可能已经开通过企业。实测踩过——以为要注册，
> 结果企业早就建好了，白走一遍注册流程。判据：打开后台如果直接进到管理首页（左侧有「我的企业 / 通讯录 / 协作」），
> 说明**已登录且有企业**，直接从第 3 节开始，不要重新注册。

### 1.4 这个后台的结构特点（先记住，后面全靠它）

- 后台是**单页应用，hash 路由**：`https://work.weixin.qq.com/wework_admin/frame#<路由>`。
  **能直接 `go_to_url` 到已知路由**，不用一层层点菜单（比点菜单稳得多，见 8.5）。
- 常用路由（实测可用）：

| 页面 | hash 路由 |
| --- | --- |
| 企业信息 | `#profile` |
| 认证中心 / 前往认证 | `#/authCenter/?from=cert_vbutton` |
| 订单详情 | `#/authCenter/orderDetail?order_id=<订单号>` |
| 企业域名 | `#profile/domain` |

- **内容区是内层滚动容器，不是 `window`**：见 8.1，这条不记住会在滚动上白花很多时间。

## 2. 登录与认证入口

### 2.1 登录：交给人

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame"}},
  {"wait":{"seconds":4}}
]}}
```

然后**请人在弹出的 Chrome 窗口里扫码/登录**。正式做法是用人机协同 API，不要像我第一次那样自己截图再在聊天里贴图片地址：

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请在弹出的浏览器窗口里扫码登录企业微信管理后台","timeoutSeconds":300}}
```

- `request_human_input` 会把当前页签**带到窗口最前**，人一眼就能看到该操作哪一页；传 `selector`/`index`
  还会把该元素截图落盘并给出 `data.imageUrl`（二维码场景用它，见 5.2）。内联的 `data.imageBase64`
  **默认已经不回了**（一张图几 KB 到几十 KB，白占上下文），确实要喂视觉模型才传 `inline: true`。
- 登录成功的判据：页面左侧出现「我的企业 / 通讯录 / 协作 / 应用管理 / 安全与管理」这套菜单，
  并且 `#profile` 能读到企业全称。**不要靠 `document.title` 判断**（标题一直是「企业微信」）。
- 登录态留在共享 profile 里，**后续任务不用再登**；换 id、换任务都不影响（主技能对应分册最后一条）。

### 2.2 进认证入口

两条等价的路，优先第二条：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/?from=cert_vbutton"}},
  {"wait_for_stable":{"quietMs":800,"timeoutSeconds":20}},
  {"execute_js":{"body":"return {url:location.href, head:document.body.innerText.slice(0,400)}"}}
]}}
```

- 或者走界面：**我的企业 → 企业信息 → 右上角「前往认证」**。
- 企业信息页上「前往认证」按钮旁边有一行小字是**当前认证状态**：
  - 未认证：只有「前往认证」；
  - 已认证：`当前认证有效期至 <日期>`（实测认证通过后这里显示到次年同日）。

### 2.3 入口分叉：先判断「现在是哪种状态」，别猜

打开后台会落到下面三种状态之一，**动作完全不同**，先花一次调用判定：

```js
(() => {
  const t = document.body.innerText;
  return {
    url: location.href,
    hasAdminNav: /我的企业/.test(t) && /通讯录/.test(t),   // 已登录且有企业
    hasCertValid: /当前认证有效期至/.test(t),               // 已认证且在有效期内
    needCert: /前往认证/.test(t),
    looksLikeLogin: /扫码登录|微信登录|手机号登录/.test(t),
    looksLikeSignup: /注册|开通|创建企业/.test(t) && !/我的企业/.test(t),
    head: t.slice(0, 300)
  };
})()
```

| 判据 | 状态 | 接下来做什么 |
| --- | --- | --- |
| `looksLikeLogin` | 未登录 | 交人登录（2.1），登录后再判一次 |
| `hasAdminNav && needCert` | **已有企业、未认证** | 直接进 2.2 走认证（**最常见的情况**） |
| `hasAdminNav && hasCertValid` | 已有企业、已认证 | 看有效期；要续办走「重新认证」，否则**任务已无必要**，先问用户 |
| `looksLikeSignup` | 还没有企业 | 先注册/开通企业（见下） |

**「已有企业」这条最容易误判**：实测踩过一次——按「注册」的思路准备了一整套流程，
结果打开后台发现企业**早就建好了**（左侧菜单齐全、企业信息页能读到全称），
白白多绕一圈。所以**判断状态这一步不要省**。

> **关于注册本身**：企业微信的「注册」= 用微信/手机号开通一个企业，填写企业名称、行业类型、员工规模等，
> 之后可以邀请成员。这一步同样需要**人扫码授权**，属于第 0 节「由人做」的范畴。
> 如果判定结果确实落在「还没有企业」，**先把需要人做的扫码授权交出去**，拿到企业上下文之后
> 再按本文第 3 节往下走——认证部分的写法与「已有企业」完全一致，不用改脚本。

## 3. 认证表单填写

### 3.1 步骤总览

| 步 | 名称 | 关键点 |
| --- | --- | --- |
| 1 | 主体信息 | 主体类型、企业全称、统一社会信用代码、管理员姓名/手机/邮箱 |
| 2 | 营业执照 + 法人验证 | 上传营业执照（**最坑，见第 4 节**）、法人扫脸（**人做**） |
| 3 | 确认名称 | 企业简称 + 简称命名依据，最后「确认并提交」 |
| — | 提交后 | 跳转支付（¥300，**人做**） |

### 3.2 先找到 Vue 实例——本站点最好用的诊断手段

认证表单是一个 Vue 组件，实例上挂着完整的 `formData`。**回读字段、判断「值到底进没进模型」，读它最准**：

```js
// 定位认证表单的 Vue 实例（组件名/根节点可能随版本变，按 formData 特征找最稳）
(() => {
  const roots = [document.querySelector('#app'), document.body].filter(Boolean);
  const seen = new Set(); const hits = [];
  const walk = (el) => {
    if (!el || seen.has(el)) return; seen.add(el);
    const vm = el.__vue__;
    if (vm && vm.formData && ('socialcredit_code' in vm.formData || 'subject_name' in vm.formData)) hits.push(vm);
    for (const c of el.children) walk(c);
  };
  roots.forEach(walk);
  const vm = hits[0];
  if (!vm) return { found: false };
  return { found: true, component: (vm.$options && vm.$options.name) || '-', formData: vm.formData };
})()
```

返回的 `formData` 实测长这样（**字段名就是契约，写脚本时照抄**）：

```jsonc
{
  "customer_type": "1",                       // 1 = 企业
  "subject_name": "<企业全称>",
  "socialcredit_code": "<18 位统一社会信用代码>",
  "subject_name_verify_type": "1",            // 1 = 法人扫脸
  "legalperson_face_info": {
    "legalperson_name": "<法人姓名>",
    "legalperson_idcard": "<法人身份证号>",
    "verify_result": 1,                       // 1 = 扫脸通过
    "isAuthLegalShare": 1
  },
  "mp_operator_name": "<管理员姓名>",
  "mp_operator_phone": "<管理员手机号>",
  "mp_operator_email": "<管理员邮箱>",
  "business_license_stuff": ["wwverify_v2_..."]   // 营业执照上传后的 filekey 数组
}
```

- **这是「填完对不对」的权威答案**：DOM 上有值、`formData` 里没有，就是没进模型（主技能对应分册）。
- `formData.business_license_stuff` 是**数组**，空数组 = 营业执照没上传成功，哪怕页面上看着有图。

### 3.3 主体信息

- 字段一律用 `input_text_by_selector`（可见字段默认走真实输入，`committed=true`）；
  **不要用 `execute_js` 直接改 `input.value`**，那样 Vue 的 model 里是空的（主技能对应分册）。
- 中文一律不要用 `send_keys`（只认键名，塞中文得到 `Unknown key`）。
- 填完先用 `get_form_state` 对一遍，再回读一次 3.2 的 `formData`：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_form_state":{"includeHidden":true}},
  {"execute_js":{"body":"return {url:location.href, txt:document.body.innerText.slice(0,600)}"}}
]}}
```

- **信用代码最容易错**：18 位，字母数字混排，读一遍确认位数（`get_element_value` 回读后 `.length === 18`）。
- 企业全称必须是**营业执照上的全称**（含「有限公司」这类后缀），简称另填（见 3.4）。

### 3.4 确认名称（第 3 步）

- **企业简称**：填 `<企业简称>`。规则是**基于企业全称里的工商字号**命名，不是随便起。
- **简称命名依据**：是个下拉/选项，实测 `801` = **基于全称中的工商字号命名**。选错会被驳回。
- 简称**一年只能改一次**（改完就消耗掉了），所以确认页要停下来问用户，别自己拍。
- 最后一步按钮是「确认并提交」，**点它之前先做完第 6 节的自检**。

### 3.5 非文本控件的处理顺序（单选 / 下拉 / 勾选）

认证表单里除了输入框，还有单选（主体类型、验证方式）、选项（简称命名依据）、勾选框（协议）。
**按这个顺序试，不要一上来就写 JS**：

1. **先判它是不是原生控件**——是 `<select>` 还是自绘下拉，写法完全不同：

```js
(() => { const out = [];
  for (const e of document.querySelectorAll('select,input[type=radio],input[type=checkbox]')) {
    const r = e.getBoundingClientRect();
    out.push({ tag: e.tagName, type: e.type || '-', id: e.id || '-', name: e.name || '-',
      checked: e.checked === undefined ? null : e.checked, selectedText: e.selectedOptions && e.selectedOptions[0] ? e.selectedOptions[0].text : null,
      y: Math.round(r.top), visible: e.offsetParent !== null });
  }
  return out.slice(0, 20); })()
```

2. **原生 `<select>`** → 用 `get_dropdown_options` 拿选项文本，再 `select_dropdown_option` 按文本选
   （主技能对应分册）。**但别假设它一定是原生 select**：本后台大量控件是自绘的，
   `get_dropdown_options` 会拿不到选项，这时走第 3 步。
3. **自绘下拉**（`div`/`span` 当触发器，选项是一堆 `li`/`div`）→ **真实鼠标点开，再点选项文本**，
   分两次调用、中间不要插别的动作（菜单会收起来）：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"click_element_by_selector":{"selector":"<下拉触发器选择器>","mode":"mouse"}},
  {"wait_for_stable":{"quietMs":500,"timeoutSeconds":10}},
  {"click_element_by_text":{"text":"<选项文本>","mode":"mouse"}},
  {"execute_js":{"body":"return {txt:document.body.innerText.slice(-300)}"}}
]}}
```

4. **单选 / 勾选** → 用 `check_element_by_index` 或直接点它的 `<label>`；
   **点完等 1–2 秒再回读**（框架状态更新是异步的，主技能对应分册），
   用 `is_checked` 或回读 3.2 的 `formData` 交叉验证。
5. **协议类勾选框必须真的勾上**再提交（第 8.7 节）。漏勾的表现是提交按钮点了没反应、
   或者报一句含糊的「请先阅读并同意」。

**判据统一回落到 3.2 的 `formData`**：控件点了、页面上看着选中了，但 `formData` 里没变，
就是没进模型——换成真实鼠标点（`mode:"mouse"`）再试，别继续在 JS 派发上打转。

## 4. 营业执照上传

实测：`upload_file` 明明返回 `ok:true`、
`input.files` 也设进去了、`change` 事件也派发了，页面却**一直停在 `image_uploader form_err` + 「请上传工商营业执照」**。

### 4.1 诊断：文件已放入但组件未读取

`upload_file` 后若仍显示「请上传工商营业执照」，检查目标输入框、`input.files` 与 `formData.business_license_stuff`。用 `get_element_listeners` 和回执的 `consumed` 辅助判断；`_vei` 是框架私有字段，缺失不能单独证明没有监听器。确认当前组件具有下面的方法后再采用 4.2 的写回方案，避免重复上传。

### 4.2 正解：直接调组件方法，再复刻它的状态写回

两步，**两步都不能少**：

```js
(async () => {
  // ---------- 准备：拿到组件实例 ----------
  const inp = document.querySelector('.uploadInput');
  if (!inp) return { err: 'no uploadInput' };

  // 组件实例一般挂在 input 的某个祖先上，沿父链找带 upload 方法的那个
  let vm = inp.__vue__;
  for (let p = inp.parentElement; !vm && p; p = p.parentElement) {
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  }
  if (!vm) return { err: 'no vue instance with upload()' };

  // ---------- 1) 直接把 files 喂给组件的 upload 方法 ----------
  // 注意：upload_file 已经把文件塞进 input.files 了；这里直接复用
  const f = inp.files;
  if (!f || !f.length) return { err: 'input.files empty — 先用 upload_file 把文件放进去' };

  const filekey = await new Promise((resolve, reject) => {
    vm.upload({ files: f },
      (res) => resolve(res),                 // 成功回调：组件会拿到 filekey
      (err) => reject(err));
  });
  // 组件内部真发 POST /wework_admin/wwAuth/upload_img
  // → 200 {"filekey":"wwverify_v2_...","filename":"<营业执照文件名>"}

  // ---------- 2) 复刻组件自己的状态写回（不做这步，formData 不会更新）----------
  vm.list.push({ key: filekey, loadingIndex: null });
  vm.emitChange();   // 等价于 this.$emit("change", list.filter(x => !x.loadingIndex).map(x => x.key))

  return { filekey, list: vm.list };
})()
```

要点：

1. **`vm.upload({files}, onSuccess, onError)` 的入参形状是 `{files: FileList}`**，不是裸 FileList；
   回调是**两个函数**（成功/失败），不是 Promise——上面用 `await new Promise(...)` 把它包成同步等待。
2. **上传接口**：`POST https://work.weixin.qq.com/wework_admin/wwAuth/upload_img`
   → 200 `{"filekey":"wwverify_v2_<...>","filename":"<文件名>"}`。**`filekey` 才是要进表单的东西**，不是文件名。
3. **`vm.list.push({key, loadingIndex: null})` 里的 `loadingIndex: null` 不能省**：
   `emitChange` 会 `filter(x => !x.loadingIndex)`，留着非 null 的 loading 标记会被过滤掉。
4. **`vm.emitChange()` 是让数据进表单的关键**：它 `$emit("change", [...keys])`，
   父组件（`MainValidate`）收到后才会把值写进 `formData.business_license_stuff`。
   **不做这一步，上传成功了但表单仍是空的**——这是第二个容易漏的点。
5. 有些版本 `vm.list` 可能是别的名字（`fileList` / `items`）。**先回读一次确认**：

```js
(() => { const i = document.querySelector('.uploadInput');
  let vm = i && i.__vue__;
  for (let p = i && i.parentElement; !vm && p; p = p.parentElement)
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  return vm ? { methods: Object.keys(vm.$options.methods || {}),
                dataKeys: Object.keys(vm.$data || {}) } : { err: 'no vm' };
})()
```

### 4.3 怎么验证「真的上传成功了」

三个判据，**按可靠性排序**：

```js
(() => {
  // ① 最可靠：Vue 的 formData 里有非空 filekey
  const i = document.querySelector('.uploadInput');
  let vm = i && i.__vue__;
  for (let p = i && i.parentElement; !vm && p; p = p.parentElement)
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  const keys = vm ? (vm.list || []).map(x => x.key).filter(Boolean) : [];

  // ② 次可靠：组件的根节点类名里不该再有 form_err
  const root = document.querySelector('.image_uploader');
  const cls = root ? String(root.className) : null;

  // ③ 最不可靠：页面上有没有出现文件名
  const pageHasFile = /<营业执照文件名片段>/.test(document.body.innerText);

  return { keys, rootClass: cls, stillErr: /form_err/.test(cls || ''), pageHasFile };
})()
```

- **`stillErr: true` 或 `keys: []` 就是没成**，别因为「页面上看着有图」就往下走。
- 页面出现「请上传工商营业执照」这句红字，等价于 `.form_err` 仍在。

### 4.4 坑：选错上传输入框

页面上**同时挂着多个 `input[type=file]`**，实测的 id 选择器是 `#dsh_up_0` / `#dsh_up_1`：

| id | 用途 |
| --- | --- |
| `#dsh_up_0` | **营业执照**（要的就是这个） |
| `#dsh_up_1` | 补充证明材料（传这里等于白传） |

- 快照里它们都显示成 `<input type=file />`，**看不出区别**。用属性确认：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_browser_state":{}},
  {"get_element_attribute":{"index":30,"name":"id"}}
]}}
```

  实测 `get_element_attribute(index, "id")` 回 `#dsh_up_1` —— 这就是传错了。
- **更稳的做法：根本不要按索引传，直接用选择器**（主技能对应分册）：
  用 `document.querySelectorAll('input[type=file]')` 结合它在 DOM 里的位置/祖先类名挑出营业执照那个，
  给它一个临时 id 再 `upload_file` 传 `selector`。
- **绝对不要靠「第几个 input」这种顺序假设**：布局一变顺序就变。

### 4.5 坑：目标输入框不在视口里、快照里没有它

- 实测 `#dsh_up_0` 一度在**视口上方**（`y = -192`），于是**它不在快照里、没有索引**，
  按索引怎么都传不进去；直到页面布局变化后才出现索引 `[44]`。
- **原因**：快照只覆盖当前视口，视口外的元素不进快照（主技能对应分册的「元素在快照里找不到」）。
- **应对顺序**：
  1. **首选：用 `selector` 而不是 `index`** —— `upload_file` 传 `selector` 不需要元素可见、也不需要索引；
  2. 需要索引时，用 `viewportExpansion` 扩大快照范围，或先把元素滚进视口（滚动见 8.1）；
  3. 确认元素位置用 `execute_js` 读 `getBoundingClientRect()`，比猜快。

```js
(() => { const es = [...document.querySelectorAll('input[type=file]')];
  return es.map((e, i) => { const r = e.getBoundingClientRect();
    return { i, id: e.id, accept: e.accept, cls: String(e.className).slice(0, 30),
             y: Math.round(r.top), visible: e.offsetParent !== null }; }); })()
```

### 4.7 上传排查

| 现象 | 检查与处理 |
| --- | --- |
| `input.files` 为空 | 检查服务器路径及营业执照输入框选择器 |
| 文件非空、组件列表为空 | 核对监听器和组件方法，按 4.2 上传并写回 |
| 组件列表非空、表单字段为空 | 检查 `emitChange` 与 `formData.business_license_stuff` |
| 找不到组件实例 | 核对当前版本的祖先实例、方法名和列表字段，不套用旧代码 |
| 快照没有上传框 | 隐藏或视口外输入框用选择器，见 4.4、4.5 |

## 5. 短信验证码与法人扫脸（都由人做，但流程要写对）

### 5.1 短信码交接

用 `request_human_input` 请用户处理验证码，保留有头页面；参数与答复处理见 [人机协同分册](../deepseek-browser-use/references/human-in-loop.md)。不要将验证码写入技能或报告。

### 5.2 法人扫脸（二维码）

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请法定代表人用微信扫描页面上的二维码完成人脸识别","selector":"<二维码元素选择器>","timeoutSeconds":600}}
```

- 二维码元素用 `get_element_screenshot` 单独取也行（走 Playwright 元素截图，不受 canvas 跨域污染限制），
  但**优先用 `request_human_input` 一次把图和提示都给出去**。
- **把二维码贴进聊天框（用户明确要求过）**：`request_human_input` 回执里有 `data.imageUrl`
  （形如 `/data/<任务id>/shot-N.png`）。**补上绝对地址**，用 markdown 图片语法贴到回复里：

  ```markdown
  ![扫脸二维码](http://localhost:10049/data/1001/shot-1.png)
  ```

  这样用户**在对话里就能直接扫码**，不用去浏览器窗口找。同时仍然 `bring_to_front` ——
  二维码有短时效，有头窗口里那一份是最新的。
  **不要**去要 `data.imageBase64`（几十 KB，且模型多半读不了图），也用不着读它——要图就给 URL。
- **必须是法定代表人本人**扫脸。实测这一步完成后 `formData.legalperson_face_info.verify_result` 变成 `1`——
  **用这个字段判断扫脸过没过**，不要看页面文案。
- 扫脸超时/失败会重置，重新发起即可；不要让智能体去"重试人脸识别"。

### 5.3 短信验证码会过期——拿到就立刻提交

**实测踩过**：第一次拿到的验证码在提交时报 **「短信验证码过期」**，
重新点「重新发送验证码」拿到新码后**立刻**提交才成功。两次验证码之间的间隔只有几分钟，
所以**不要以为「码还在手上」就慢慢来**。

- 验证码**有时效**（主技能对应分册提到税务系统的图验约 120 秒；短信码同样是分钟级），
  所以流程是：`request_human_input` → 人给码 → **`submit_human_input` 之后立刻 `input_text` + 点提交**，
  不要中间插别的动作、不要攒着。
- 过期了就**重新发一次要新码**，**不要拿旧码反复重试**（重试必然失败，还会浪费一轮）。
- 提交后判断结果别只看 `ok:true`：用 `expect` 断言（见 6.2）或回读页面文案。

## 6. 提交前自检与支付

### 6.1 提交前的自检清单

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_form_state":{"includeHidden":true}},
  {"execute_js":{"body":"return {url:location.href, txt:document.body.innerText.slice(-1200)}"}}
]}}
```

逐条核对：

1. `customer_type === "1"`（企业）；
2. `formData.subject_name` 与营业执照全称逐字一致；
3. `formData.socialcredit_code` 是 18 位且与营业执照一致；
4. `formData.business_license_stuff` **非空**（见 4.3）；
5. `legalperson_face_info.verify_result === 1`（见 5.2）；
6. `formData.mp_operator_phone` / `formData.mp_operator_email` 是用户给的；
7. 第 3 步的**企业简称 + 命名依据**已确认（见 3.4）。

### 6.2 提交后核验

用户完成提交后回读订单与审核状态。`expect` 可以等待具体后置条件；断言失败可能是等待不足或条件错误，不证明提交未发生，先观察再决定下一步。

### 6.3 支付：由人做

- 提交后进入**订单/支付页**，是**微信支付扫码**（`支付方式: 微信支付`）。**这一步只能由人完成**，智能体不要代付。
- 交接话术要写清：需要人**用微信扫码支付 ¥300**，并说明「认证生效一年」。
- 支付完成后**不要立刻假设成功**——按第 7 节去订单页回读状态。

### 6.4 支付刚完成时要做的三件事

人扫码付完之后，**立刻**在一个批次里做完这三步，别等：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":25}},
  {"execute_js":{"body":"const t=document.body.innerText;const i=t.indexOf('支付方式');return {url:location.href,seg:i>=0?t.slice(i-200,i+900):t.slice(-900)};"}},
  {"get_requests":{"filter":"authCenter","limit":10}}
]}}
```

1. **拿到并记下订单号**（`order_id=<订单号>`）——后面查审核、开发票、申请合同全靠它；
2. **确认订单显示已支付或相应业务状态**；仅出现「支付方式: 微信支付」不能证明付款完成；
3. **把订单号脱敏后写进交付说明**（只留后 4 位）。

**支付没走完的两种情况**：

- **人还没付**：订单会停在待支付。**不要代替人重试支付、不要连点支付按钮**——把状态告诉用户，等他付完再回读。
  重新进订单页的路径就是上面的 `go_to_url`，比在界面上找入口稳。
- **付了但页面没跳**：不要凭「页面没变」判断失败。按上面批次**直接 `go_to_url` 订单详情页回读**，
  这才是权威状态（主技能对应分册：`ok:true` 与 `changed` 都不证明业务结果）。

> **订单号是这一整条线的锚点**：支付、审核进度、发票、合同四个页面都挂在它上面。
> 一旦拿到就记进任务笔记，别指望后面再从界面里翻出来。

## 7. 查审核结果

### 7.1 订单详情页

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":25}},
  {"execute_js":{"body":"const t=document.body.innerText;const i=t.indexOf('订单编号');return {url:location.href, seg:i>=0?t.slice(i,i+1200):t.slice(-1200)}"}}
]}}
```

能读到：订单编号、支付方式、有效期、认证规模、认证主体、申请人、下单时间、**订单合同**、**订单发票**、审核机构。

### 7.2 判断审核过没过的三个判据

| 判据 | 位置 | 说明 |
| --- | --- | --- |
| **订单发票**从「审核完成后可申请」变成「**申请发票**」 | 订单详情页 | **最灵敏的信号**：能申请发票 = 审核已结束 |
| 企业信息页出现 **`当前认证有效期至 <日期>`** | `#profile` | 出现即已通过，日期通常是下单日 + 一年 |
| `重新认证` 按钮出现 | 订单详情页 | 说明这一单已经走完 |

> 实测：审核期间订单页的「订单发票」写的是「审核完成后可申请」；过一段时间再打开，它变成了「申请发票」，
> 同时 `#profile` 出现「当前认证有效期至 <日期>」。**别在审核期间反复刷页面**，隔几小时看一次就够。

### 7.3 发票与合同

认证完成后按用户需求转 [发票、合同与电子签章技能](../wecom-invoice-contract/SKILL.md)。先核实可申请状态及已确认的发票类型；电子合同依赖企业电子签章。

### 7.4 审核被退回 / 迟迟没有结果

- **审核主体是第三方机构，不是企业微信**：订单页上会写明审核机构名称、咨询电话、邮箱、客服微信号与
  **咨询时间（工作日 9:30~17:30）**。要催办或问退回原因，走这些渠道，**不要在企业微信里找入口**。
- **常见退回原因**（都对应本文前面某一节，可以自己先查一遍再找客服）：

| 退回原因 | 自查位置 |
| --- | --- |
| 营业执照不清晰 / 公章不可辨 / 传成了补充材料 | 4.3、4.4 |
| 企业全称或信用代码与营业执照不一致 | 3.3 |
| 法人扫脸未通过 / 非法人本人 | 5.2（回读 `formData.legalperson_face_info.verify_result`） |
| 企业简称不符合命名依据 | 3.4（`801` = 基于全称中的工商字号命名） |

- **退回后一般可以「重新认证」**：订单详情页有 `重新认证` 入口。重新走一遍时，
  **不要从零开始**——先回读 3.2 的 `formData`，多数字段还在，只需要改被退回的那一项。
- **不要高频轮询审核状态**：审核是人工的，隔几小时看一次足够。
  实测「订单发票」的文案变化（见 7.2）是最省事的信号，看它比翻整个订单页还快。

## 8. 该站点的通用坑与写法

### 8.1 内容区是内层滚动容器，`window.scrollTo` 无效

**实测**：`window.scrollTo(0, 0)` 执行后 `window.scrollY` **一直是 0**，而目标元素还在 `y = -192`
（即仍在视口上方）——后台的滚动发生在**内层容器**上，不是 `window`。

```js
// 找出真正的滚动容器（scrollHeight 明显大于 clientHeight 的那个）
(() => { const out = [];
  for (const e of document.querySelectorAll('div')) {
    if (e.scrollHeight - e.clientHeight > 100 && e.clientHeight > 200) {
      const s = getComputedStyle(e);
      if (/auto|scroll/.test(s.overflowY)) out.push({ cls: String(e.className).slice(0, 40),
        scrollTop: e.scrollTop, sh: e.scrollHeight, ch: e.clientHeight });
    }
  }
  return out.slice(0, 6); })()
```

**但更好的做法是根本不滚动**：

- 需要哪个元素就用 **`click_element_by_selector` / `input_text_by_selector` 按 id/class 定位**——
  这类方法不依赖快照索引，元素在视口外也照样能操作（主技能对应分册就是为这种情况写的）。
- 只有「必须让元素进视口」时（例如要截图给人看）才去操作内层容器：

```js
(() => { const box = document.querySelector('<内层容器选择器>');
  if (box) box.scrollTop = 0; return { scrollTop: box ? box.scrollTop : null }; })()
```

### 8.2 弹窗识别：`get_modals` 不认企业微信自己的弹窗类名

主技能里 `get_modals` / `close_modal` 的选择器是 **ant-design / Element-UI / vxe / layui 专用**的
（`.ant-modal-wrap`、`.el-dialog`、`.layui-layer`、`[role=dialog]`…）。而企业微信后台用自己的类名：

| 弹窗 | 实际类名 |
| --- | --- |
| 发票申请弹窗 | `.mall_invoice_dialog_container` |
| 通用确认框 | `.qui_dialog` / `.ww_dialog` 之类 |

**所以 `get_modals` 返回 `count: 0` 不等于「没有弹窗」**——它只是没命中。这种情况自己扫可见浮层：

```js
// 扫可见浮层：position:fixed 或高 z-index，且有实际尺寸
(() => { const out = [];
  for (const e of document.querySelectorAll('div,section')) {
    const s = getComputedStyle(e);
    if (s.display === 'none' || s.visibility === 'hidden') continue;
    const z = parseInt(s.zIndex);
    if (!(s.position === 'fixed' || (!isNaN(z) && z > 50))) continue;
    const r = e.getBoundingClientRect();
    if (r.width < 120 || r.height < 50) continue;
    out.push({ cls: String(e.className).slice(0, 50), z: s.zIndex,
      x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height),
      txt: (e.innerText || '').slice(0, 120) });
  }
  return out.slice(0, 8); })()
```

- **先 `close_modal` 试一次**（它内部用真实鼠标点，且会校验弹窗数量是否真的减少），
  `closed:false` 再退回上面的自扫 + `mouse_click` 坐标。
- 关不掉的企业微信弹窗，**用 `mouse_click` 点它的关闭按钮坐标**（`get_modals` 给的 `closePoint` 思路一样，
  只是坐标要自己从 `getBoundingClientRect()` 算）。
- **提交/支付类按钮不要用 JS 派发去点**：主技能对应分册说过，`mode=js` 意味着没走真实交互。
  关键动作用 `mode:"mouse"`。

### 8.3 索引随时失效，回读要用选择器

- 认证表单是分步的，**每点一次「下一步」索引全部重算**（主技能对应分册）。沿用旧索引会得到
  `click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引`。
- **实测踩过**：上一批次拿到的 `[35]`，下一次调用就报上面这句；同一个元素换成
  `click_element_by_selector` 或 `document.querySelector(...)` 立刻就好。
- 结论：**这个站点上优先用选择器定位，把索引当一次性用品**。

### 8.4 点击结果

`changed` 与网络埋点不能证明提交成功；回读本次订单、表单或审核状态。动作不确定时先观察，不重复提交。

### 8.5 hash 路由可以直接跳，比点菜单稳

- 后台是 SPA，`go_to_url` 到 `#profile` / `#/authCenter/?from=cert_vbutton` / `#/authCenter/orderDetail?order_id=...`
  都能直达。**点菜单反而容易翻车**（菜单是纯 JS 事件、有 hover 展开、还常弹二级）。
- 跳转后等 `wait_for_stable`（不是固定 `wait`），再回读 `location.href` 确认真的到了。

### 8.6 校验纪律：以 Vue 的 `formData` 为准，不以 DOM 为准

- **DOM 里有值 ≠ 进了框架模型**（主技能对应分册）。这个站点的权威答案在 3.2 那个 `MainValidate.formData` 里。
- `input_text_by_selector` 对可见字段默认走真实输入（`committed=true`）；
  看到 `data.committed=false` 就重填一遍，别继续加值。
- 密码类字段快照里是 `[redacted]`，别指望从快照读出来。

### 8.7 弹窗、协议层会挡住点击

- 主技能提到过「用户服务协议」这类覆盖层会让 `data.coveredBy` 指向别人——
  **此时你以为在点的按钮其实被它挡着**。回执里 `data.coveredBy` 有值就先关遮挡物再点，
  而不是反复点同一个按钮。
- 企业微信后台的协议勾选（如电子签章要勾《系统使用须知》《电子认证协议》《用户隐私协议》）
  **必须真的勾上**再提交，勾选后等 1–2 秒再回读 `is_checked`（框架状态更新是异步的）。

### 8.8 常见业务提示

- 「请上传工商营业执照」：按第 4 节检查目标输入框及表单 filekey。
- 「短信验证码过期」：重新获取并及时处理，不重试旧码。
- 提交无响应：先查订单是否已创建，再检查协议、遮挡与字段错误。通用 CLI / 索引故障见主技能分册。

## 10. 交付

报告表单、付款、审核各自的实际状态及认证有效期；「已提交」不等于「已通过」。给出待办的扫脸、短信码、支付或最终确认步骤，发票和合同按 7.3 转入对应技能。敏感记录按第 11 节处理。

## 11. 脱敏约定

本文所有示例都是占位符：`<企业全称>`、`<18 位统一社会信用代码>`、`<法人姓名>`、`<法人身份证号>`、
`<管理员手机号>`、`<管理员邮箱>`、`<订单号>`、`<企业ID>`、`<企业简称>`、`<营业执照文件名>`。

- **不要把真实值写回技能文档**：真实公司名、统一社会信用代码、法人身份证号、管理员手机号/邮箱、
  订单号、企业 ID、**本机绝对路径**，一律用上面的占位符。
- 给用户看的过程记录里，信用代码/手机号/订单号**只留后 4 位**，身份证号**直接省略**。
- 营业执照扫描件与 logo 这类材料**不要放进技能仓库**，放临时目录，用完清理。
- 服务端追踪日志默认脱敏（手机号、18 位证件号/统一社会信用代码、邮箱、长数字 → `***`），
  可用 `browser.trace.redact` 追加公司名等自定义正则，但**姓名、门牌号掩不掉**，共享前自己过一眼。
