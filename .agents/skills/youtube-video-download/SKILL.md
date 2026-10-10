---
name: youtube-video-download
description: 用 dsb 与 yt-dlp 下载用户有权保存的 YouTube 视频；区分公开视频与会员内容、频道最新与栏目最新，安全导出登录凭据、先列格式告知降级、收集后台任务并用 ffprobe 验收。
---

# YouTube 视频下载

## 边界与已验证经验

仅保存用户有权下载的内容，不绕过会员权限。需要浏览器操作时先加载 deepseek-browser-use 技能，由一个所有者独占已交接的数字任务 ID；登录和身份认证交给用户。本文自包含；命令是占位模板，不使用实际账号、视频 ID 或主机路径。模板及安全加固建议未经本次执行验证，不当作历史逐字命令。

- Windows 普通 Chrome 人工登录成功，自动化窗口曾被 Google 拒绝；不能据此认定自动化是唯一原因。不能保证 passkey 或关闭调试必可用；二步验证不证明未建 passkey。
- 同 profile 的 Chrome 单实例会接管新启动，必须核实实际进程参数与 profile。需要切换时协调所有者正常关闭并确认退出，不能强杀全部 Chrome、关闭其他任务或重启共享服务。
- 全局 Anaconda 安装 yt-dlp[default] 曾导致 requests 冲突；建议独立 venv，任何安装须用户授权。
- --cookies-from-browser 提取 318 条 cookies 仍失败；同一已登录浏览器的 YouTube 播放可用。缺少 SID 不证明未登录，数量不证明凭据有效。
- 已验证的成功组合：dsb 按 YouTube URL 导出 → Netscape 转换 → 显式本机代理 + ``youtube:player_client=web``。不能单独归因 cookie 或 client，不能保证重复成功。历史结果为 **360p、H.264、AAC、2508.684 秒、169766681 字节、正常退出，不是 1080p**。

## 1. 明确目标与环境

先问清目标 URL、输出目录、清晰度、降级授权。频道最新需明确视频/直播/Shorts范围并核实发布时间；栏目最新需核实栏目归属和期次，不拿别的栏目替代，不将置顶或列表第一条自动当最新。记录观察时间，覆盖不全只报告已查范围。

公开视频可先无凭据列格式（未验证建议）；会员内容先由有相应权限的用户在同一登录浏览器确认目标可播放，普通登录不等于会员授权。

```powershell
# 仅在获准安装后执行；若已有独立环境则复用，不改全局环境。
python -m venv .venv-youtube
if ($LASTEXITCODE -ne 0) { throw "创建环境失败" }
$Python = ".\.venv-youtube\Scripts\python.exe"
& $Python -m pip install "yt-dlp[default]"
if ($LASTEXITCODE -ne 0) { throw "安装失败" }
& $Python -m pip check
if ($LASTEXITCODE -ne 0) { throw "依赖冲突" }
$VideoUrl = "https://www.youtube.com/watch?v=<VIDEO_ID>"
$Proxy = "http://127.0.0.1:<PROXY_PORT>"
$OutputDir = "<用户指定输出目录>"
$FfmpegDir = "<已安装的FFmpeg工具目录>"
$Ffprobe = "<已安装的ffprobe可执行文件>"
# 公共内容的可选无凭据查询；失败后先诊断，不自动升级为凭据导出。
& $Python -m yt_dlp --ignore-config --no-playlist --proxy $Proxy --list-formats $VideoUrl
$PublicExit = $LASTEXITCODE
```

确认本机代理地址与端口实际可用且获准使用；缺少 FFmpeg/ffprobe 则另行请求安装授权。

## 2. 保护凭据后导出并转换

`get_cookies` 导出、显式 --out、dsb 留档及服务端 trace 都可能是敏感数据。**不打印正文、不提交、不上传**，不得把文件放入仓库、同步盘或 Web 静态目录。推荐 --no-record；它不保证服务端/宿主记录安全，导出前核实日志策略，不能保证安全就停止。

以下是未验证的安全模板：随机临时目录先收紧为当前用户权限，核实 ACL 后再导出；复用已经登录且已交接的任务，不重复启动浏览器。

```powershell
$SecretDir = Join-Path $env:TEMP ("youtube-private-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $SecretDir -ErrorAction Stop | Out-Null
$Principal = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $SecretDir /inheritance:r /grant:r "${Principal}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "权限设置失败，不得导出" }
# 在本机核实 ACL，不将身份或目录信息贴入报告。
$Raw = Join-Path $SecretDir "cookies.json"
$Jar = Join-Path $SecretDir "cookies.txt"
$Records = Join-Path $SecretDir "dsb-records"
$TaskId = "<已交接的数字任务ID>"
$Session = "<本次独占会话标签>"
dsb --id $TaskId --session $Session --no-auto-start --no-record --record-dir $Records run get_cookies -p "url=$VideoUrl" --out $Raw
if ($LASTEXITCODE -ne 0) { throw "导出失败，不打印响应正文" }

$Response = [System.IO.File]::ReadAllText($Raw) | ConvertFrom-Json
if ($Response.ok -ne $true -or $null -eq $Response.data.cookies) { throw "响应结构不符" }
$Cookies = @($Response.data.cookies)
if ($Cookies.Count -eq 0) { throw "没有可转换条目" }
$Lines = [System.Collections.Generic.List[string]]::new()
$Lines.Add("# Netscape HTTP Cookie File")
foreach ($Cookie in $Cookies) {
    if ($Cookie.value -eq "[REDACTED]") { throw "这是脱敏留档，不是可用凭据导出" }
    $Domain = [string]$Cookie.domain
    if ($Domain.TrimStart(".") -ne "youtube.com" -and -not $Domain.EndsWith(".youtube.com")) { throw "非目标域" }
    $Subdomains = if ($Domain.StartsWith(".")) { "TRUE" } else { "FALSE" }
    if ($Cookie.httpOnly) { $Domain = "#HttpOnly_" + $Domain }
    $Secure = if ($Cookie.secure) { "TRUE" } else { "FALSE" }
    $Expiry = if ([double]$Cookie.expires -gt 0) { [long][math]::Floor([double]$Cookie.expires) } else { 0 }
    $Fields = @($Domain, $Subdomains, [string]$Cookie.path, $Secure, [string]$Expiry, [string]$Cookie.name, [string]$Cookie.value)
    foreach ($Field in $Fields) { if ($Field -match "[\t\r\n]") { throw "非法分隔符" } }
    $Lines.Add(($Fields -join "`t"))
}
[System.IO.File]::WriteAllLines($Jar, $Lines, [System.Text.UTF8Encoding]::new($false))
Remove-Variable Response, Cookies, Cookie, Fields, Field, Lines -ErrorAction SilentlyContinue
```

保留 HttpOnly、域/子域、路径、secure 与到期时间，会话条目到期时间写 0；不使用 document.cookie 替代，不伪造 SID。

## 3. 先列格式，告知降级，再下载

```powershell
& $Python -m yt_dlp --ignore-config --no-playlist --cookies $Jar --extractor-args "youtube:player_client=web" --proxy $Proxy --list-formats $VideoUrl
if ($LASTEXITCODE -ne 0) { throw "格式查询失败，停止下载" }
# 核对格式列表，先告知可用清晰度；降级须取得同意。
$Format = "<FORMAT_ID或VIDEO_FORMAT_ID+AUDIO_FORMAT_ID>"
& $Python -m yt_dlp --ignore-config --no-playlist --cookies $Jar --extractor-args "youtube:player_client=web" --proxy $Proxy --ffmpeg-location $FfmpegDir --format $Format --merge-output-format mp4 --output "$OutputDir/%(id)s.%(ext)s" $VideoUrl
$DownloadExit = $LASTEXITCODE
if ($DownloadExit -ne 0) { throw "下载或合并失败" }
```

不要硬编码历史格式 ID，不静默回退低清晰度；MP4 容器不保证 H.264/AAC，不会提升分辨率。失败时分别记录浏览器播放、导出、格式查询的结果及脱敏错误，不无限重试；换 client、关闭调试或改认证方式只能作为未验证建议。

## 4. 收集、验收、清理

长下载只启动一次，保存真实宿主 job ID；超时转后台不是失败。不 busy-poll、不 sleep 循环；等完成通知或确实受阻时用宿主 ``job_output(wait:true)`` 有界收集。返回前收集所有相关 job 和退出码，不再需要的用 ``job_kill`` 取消并确认。浏览器 ID、dsb job ID、宿主 job ID、子代理 ID 不混用。

```powershell
$FinalFile = "<最终媒体文件>"
& $Ffprobe -v error -show_entries "stream=codec_type,codec_name,width,height:format=duration,size" -of json $FinalFile
if ($LASTEXITCODE -ne 0) { throw "媒体验证失败" }
$Bytes = (Get-Item -LiteralPath $FinalFile -ErrorAction Stop).Length
# 所有凭据使用者结束后，在本机核实解析路径只属于本次临时目录。
$ResolvedSecret = (Resolve-Path -LiteralPath $SecretDir -ErrorAction Stop).Path
# 核实路径和内容之后才执行，切勿删除未核实的计算路径。
Remove-Item -LiteralPath $ResolvedSecret -Recurse -Force -ErrorAction Stop
if (Test-Path -LiteralPath $ResolvedSecret) { throw "敏感临时目录清理未完成" }
Remove-Variable Raw, Jar, SecretDir, ResolvedSecret -ErrorAction SilentlyContinue
```

ffprobe 核对最终文件的实际高度、音视频编码、时长及大小，并与文件字节数对照；不把分片存在或正常退出单独当成功，不把 ffprobe 等同于逐帧完整验证。另检查并清理仅属本任务的敏感 dsb 留档/trace/宿主副本，不影响其他任务；删除不是介质级安全擦除。回传文件位置、实际质量、时长、字节数、退出码、降级授权、job收集与清理状态，绝不附原始凭据。

## CLI 留档能力与测试边界

已使用可见浏览器和合成 Cookie 验证：自动结构化 Cookie 留档值变为 `[REDACTED]`，显式 `--out` 仍保留原值；显式 `--id last` 在同一会话按配对请求筛选任务，无匹配报错。不传显式 ID 仍读取目录最新记录。此功能不覆盖任意脚本、页面文字、错误信息、服务端 trace 或历史记录，仍优先 `--no-record` 并保护导出文件。

先检查可用的 JavaScript 运行时（成功实操使用 Deno 解决 JS challenge）；`yt-dlp[default]` 不等于已安装外部运行时。缺失时先征求安装同意，不跳过这一检查或承诺任意版本兼容。

对外只展示简洁计划、必要进度、已核实事实和结论，不输出内部推演，不把尚未验证的建议写成成功经验。
