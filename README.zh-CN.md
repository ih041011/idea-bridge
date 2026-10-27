# IDEA Bridge

**给运行中的 IntelliJ IDEA 提供一个 HTTP 控制接口 —— 供 agent 和脚本使用。**

[English](README.md) · MIT 许可 · 面向 IntelliJ IDEA 2024.1（build 241）

IDEA Bridge 是一个插件：它在 IDE 进程内起一个只监听回环地址的 HTTP 服务，把「在 IDE 里写代码」
真正需要的东西暴露出来 —— 打开的工程、文件、**编辑器正在编辑的那份缓冲区**、可撤销的编辑、
Run Configuration 连同它的控制台输出，以及 IDE 自己的错误分析。

最后一项才是重点。把文件写到磁盘是个已经解决的问题；这个东西给的是 IDE 的**状态** ——
一个人正在编辑的缓冲区、它的 undo 栈、它的分析器、它的 Run 工具窗口。
通过 IDEA Bridge 做的改动，落在和人的改动完全相同的地方，所以语法高亮、代码分析、重构和 undo 栈
都和它保持一致。

## 为什么会有这个

| 现成方案 | 为什么不够 |
| --- | --- |
| JetBrains 官方 MCP Server 插件 | 要求 IDE 2025.1+；本项目面向 2024.1（build 241） |
| `idea64.exe --line N file` | 只能打开文件，不能读写缓冲区，不能运行任何东西 |
| Remote Development Server | 是给人在瘦客户端上用的，不是一个程序可以驱动的东西 |
| 直接改磁盘上的文件 | IDE 根本不知道；一份打开的缓冲区会把你覆盖掉 |
| **本插件** | 跑在 IDE 进程内，直接够到平台 API |

## 环境要求

- **IntelliJ IDEA 2024.1**（build 241）。`plugin.xml` 里写死 `since-build="241" until-build="241.*"`。
- **Python 3.8+**，用于客户端和构建脚本。
- 不需要下载 IntelliJ Platform SDK，**也不需要 Gradle** —— 构建直接用你机器上已装的那份 IDEA
  自带的 JBR 来编译。

## 安装

```bash
git clone https://github.com/ih041011/idea-bridge
cd idea-bridge
tools/build_and_install.sh
```

这个脚本会：用你机器上的 IDEA 编译插件 → 打包成 jar → 停掉 IDE → 装进去 → 重启 IDE → 等桥起来。
它会自动找到你的 IDEA；如果你的装在非常规位置，直接指给它：

```bash
IDEA_HOME="C:/Program Files/JetBrains/IntelliJ IDEA 2024.1" tools/build_and_install.sh
```

**脚本里的顺序不是装饰。** 运行中的 IDE 会锁住已加载的插件 jar，所以趁它开着复制会失败于
`Permission denied`，并且**静默地**保持旧插件不变 —— 表现得和「构建成功但没有效果」一模一样。
脚本先停 IDE，再用 md5 对比确认复制真的生效了。

之后**正常启动 IDEA 就行**，不需要任何参数、不需要启动脚本：插件挂在 `appStarted` 上，
所以欢迎界面还在的时候（任何工程都没打开）它就已经在监听了。

## 使用

```bash
python ib.py help            # 全部命令
python ib.py status          # 是否活着、打开了哪些工程
python ib.py editor.active   # 我在看哪个文件、光标在哪
```

典型的一轮编辑：

```bash
F=F:/work/src/Main.java

python ib.py editor.context "path=$F" line=42 before=6 after=6   # 先看清楚再动手
python ib.py editor.edits   "path=$F" edits=@patch.json save=false
python ib.py problems.get   "path=$F"                            # 这一步改坏什么了没有？
python ib.py editor.save    "path=$F"
python ib.py run.execute    Main
python ib.py run.console    Main waitMs=30000                    # 它到底打印了什么？
```

## 命令表

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `status` | — | 服务状态、IDE 版本与 build、打开的工程 |
| `help` | — | 命令清单（由插件自己给出） |
| `project.list` | — | 所有打开的工程 |
| `project.info` | `project` | 模块、内容根、SDK |
| `file.read` | `path` | 读文件；**文件开着就优先给未保存的缓冲区** |
| `file.write` | `path, text, createDirs, save` | 经 VFS 写；开着的文件走 document 路径 |
| `file.list` | `path, glob, recursive, limit` | 列目录，支持 `*` / `**` / `?` |
| `file.stat` | `path` | 是否存在、大小、修改时间 |
| `vfs.refresh` | `path, recursive` | 丢掉 IDE 缓存重新读盘 |
| `editor.open` | `path, line, column, focus` | 打开并定位 |
| `editor.active` | — | 当前文件、光标、选区 |
| `editor.context` | `path, line, before, after` | 取某个位置附近的几行 —— 便宜的「先看一眼」 |
| `editor.edits` | `path, edits[{start,end,text}], save` | 批量按偏移编辑，**一次 undo 可撤销** |
| `editor.save` | `path` | 存盘 |
| `editor.close` | `path` | 关掉该文件的编辑器 |
| `run.list` | `project` | 全部 Run Configuration |
| `run.execute` | `name, project` | 启动 —— 等同于点绿三角 |
| `run.stop` | `name, project` | 终止 |
| `run.console` | `name, project, maxChars, waitMs` | 控制台文本与退出码 |
| `problems.get` | `path, limit, waitMs` | **IDE 自己分析出的**错误与警告；`complete` 说明分析跑完没有 |
| `log.tail` | `lines` | 插件自身的日志 |

### 参数怎么写

`key=value`，值会先按 JSON 解析，解析失败就当字符串 —— 所以数字、布尔、`null`、嵌套数组
都能直接写，不用玩引号。

只有**第一个 `=` 前面是纯标识符**时才算 `key=value`。没有这条规则，粘贴进来的一段代码
（几乎一定含 `=`）会被切成一个乱码 key 并被丢掉，然后你得到的是一句莫名其妙的
`missing 'text'`，而不是一次成功的写入。

源码文本走**间接**传参，因为真实代码活不过一条命令行 —— 它既有 `=` 又有换行：

```bash
python ib.py file.write    path=F:/x/New.java text=@snippet.java   # 值从文件读
python ib.py editor.edits  path=F:/x/A.java   edits=-              # 值从 stdin 读
```

命令有显而易见的那个参数时，裸参数会被自动归位（`run.*` 归到 `name`，其余归到 `path`）：

```bash
python ib.py file.read      F:/x/A.java
python ib.py run.execute    MyConfig
python ib.py problems.get   F:/x/A.java limit=20
python ib.py file.list      F:/x 'glob=**/*.java' recursive=true
```

### 环境变量

| 变量 | 作用 |
| --- | --- |
| `IDEABRIDGE_HOST`、`IDEABRIDGE_PORT` | 覆盖端点（默认从 `~/.ideabridge/endpoint.json` 读） |
| `IDEABRIDGE_TOKEN`、`IDEA_BRIDGE` | 覆盖 token |
| `IDEABRIDGE_PORT`（插件侧） | 改监听端口，默认 `64343` |
| `IDEABRIDGE_TIMEOUT` | 客户端请求超时秒数，默认 `900` |

## 关键设计决定

每一条都是踩过坑才这么写的，每一条都是某段看起来平平无奇的代码之所以长成那样的原因。

1. **动文件之前先问「有没有打开的 `Document`」。** 文件在编辑器里时，磁盘那份可能是旧的，
   直接写盘会让 IDE 要么重载、要么静默丢弃改动。开着的文件走 `Document.setText` /
   `replaceString`，这样 undo 栈、PSI、高亮、分析全部同步；只有没打开的文件才写磁盘。
2. **`editor.edits` 按偏移降序应用。** 正序应用会让前面的编辑移动后面编辑的偏移量。
   同一位置的两次编辑也稳定：`[{0,0,"// first"},{0,0,"// second"}]` 可靠地得到
   `// second` 然后 `// first`。
3. **裸的 `Application.runWriteAction` 必须在 EDT 上** —— 241 会断言线程 ——
   而 `WriteCommandAction.runWriteCommandAction` 自己会切线程。请求线程是 `ideabridge-http`，
   所以 `BridgeCommands.writeAction` 先 `edt(...)` 再进写动作。
4. **VFS 刷新在任何锁下都是非法的** —— 不只是读动作，写动作里也一样 ——
   而且它是**静默跳过**而不是报错。早期版本把 `refreshAndFindFileByPath` 包在 read action 里，
   结果新建的文件返回 `vfsVisible:false`，后续所有命令都以为这个文件不存在。
   现在所有刷新都在无锁状态下调用。
5. **新建文件后先刷新父目录再找它自己**，否则新文件对 IDE 不可见。
6. **控制台文本只能从 `ConsoleViewImpl.getText()` 拿。**
   `RunContentDescriptor` 没有 `getConsoleView()`，`ConsoleView` 接口也没有 `getText()`。
7. **日志不写进 IDE 自己的日志**（它的路径随安装漂移），写 `~/.ideabridge/bridge.log`，
   超过 2MB 轮转 —— 并且**任何日志失败都被吞掉**，因为日志绝不能成为命令失败的原因。
8. **`problems.get` 必须等分析器跑完。** IDE 的高亮是**渐进**给出的：实测一个刚建的文件
   `count` 在约 2s 时是 0、4s 时 1、6s 时 3、8s 才稳定在 5。不等就等于把「还没分析」报成
   「没问题」，而这是诊断命令唯一不能犯的错。所以轮询
   `DaemonCodeAnalyzerEx.isErrorAnalyzingFinished(PsiFile)`，并把结果如实报成 `complete`。
9. **控制台要认准「本次运行」那个。** IDEA 会保留每一个已结束的运行标签页，所以按名字匹配会
   拿到**最旧**的那个，「我刚启动的程序打印了什么」就会静默读成上一次运行的输出。
   现在优先取进程还活着的；`run.execute` 时会记下当时已存在的标签页，`run.console` 只认
   快照之外的那个 —— 已经结束的新标签页也算「到了」，否则一个很快结束的程序会一直空等到超时。

## 安全

- 只绑 `127.0.0.1`。从别的机器上什么也够不到。
- `/cmd` 需要 token（`X-Bridge-Token` 头或 `?token=`），常数时间比较。
  `/ping` 免鉴权，纯粹是为了让调用方能判断 IDE 在不在。
- token 存在 `~/.ideabridge/token`；首次运行时生成 64 位 hex 并把文件权限限制为仅所有者。
- 请求体上限 32 MB。
- `BridgeException` 把「调用方要了一件做不到的事」和真正的故障分开，并刻意不带栈。
- 服务起不来时，插件**只记日志，让 IDE 照常启动**。它绝不能成为 IDEA 打不开的原因。

## 已知边界

- **只支持 IntelliJ IDEA 2024.1。** `idea-version` 写死 `241`。在 2025.1+ 上请改用 JetBrains
  官方的 MCP Server 插件 —— 本项目存在的理由正是那个插件在这里用不了。
- 除 bearer token 外没有别的认证，也没有 TLS：它假定这是单用户的机器。
- 没有 `file.delete` 和重命名命令。用 shell 删除后调 `vfs.refresh`（已验证可用）。
- 开发与测试环境是 Windows。Java 部分是平台中立的，只有构建与安装脚本会按操作系统分支。

## 开发

```bash
tools/build_and_install.sh                         # 编译、打包、安装、重启、等桥
tools/build_and_install.sh "F:\IDEA\SomeProject"   # 顺便打开某个工程
```

只编译不安装：

```bash
"$IDEA_HOME/jbr/bin/javac" -J-Duser.language=en -encoding UTF-8 \
  --release 17 -Xlint:-options -cp "$IDEA_HOME/lib/*" \
  -d build/classes plugin/src/ideabridge/*.java
```

`-J-Duser.language=en` 是让 `javac` 用英文报错；否则在代码页不匹配的 shell 里它的错误是乱码。

自带的 JBR 里没有 `jar.exe`，所以 `tools/build_plugin.py` 用 `zipfile` 写包。
描述文件必须位于 jar **内部**的 `META-INF/plugin.xml` —— jar 放在 `<plugin>/lib/` 时，
那是平台唯一会去看的地方。

### 验证

`demo-project/` 是一个用完即弃的 Java 21 工程，带一个 `Hello` 运行配置。
一条命令跑通整个闭环，并且会断言自己的前提条件：

```bash
python tools/demo_workflow.py
```

它会把目标文件重置、改活缓冲区、问分析器、保存、运行配置、再把控制台读回来。
如果编辑悄悄退化成写磁盘、或者分析始终没跑完，它会直接失败 ——
一个两种情况都接受的冒烟测试，会通过，但什么也没证明。

### 目录结构

```
plugin/src/ideabridge/     插件本体（14 个类，零第三方依赖）
plugin/resources/META-INF/ plugin.xml
ib.py                      命令行客户端
tools/                     构建、安装、路径探测、验证
tools/api-probe/           当初用来探明 241 API 的脚本；见它自己的 README
demo-project/              验证用的工程
```

### API 探测脚本

插件里的每一个 API 调用，都是在写之前先对着**机器上实际安装的那份 API** 核对过的，
因为没有下载的 SDK 可以查。[`tools/api-probe/`](tools/api-probe/README.md) 里放着让这件事成为可能的
反射导出器与资源扫描器，也包括两条走不通的路以及为什么走不通。
如果你要把这个插件移植到别的 build，它们是值得留下的。

## 许可证

[MIT](LICENSE) © 2026 ih041011

## 它是怎么做出来的

用 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 写的，而且驱动的就是
被这个插件控制的那个 IDEA 实例 —— 「关键设计决定」里记录的每一处 API 签名、扩展点名和修复，
都是边写边对着 build 241 核实出来的。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)