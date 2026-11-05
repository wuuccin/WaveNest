<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

## 通用写作规范

### 句子成分完整

助手撰写代码注释、提交说明、文档正文与对话回答时，必须写出明确的主语与谓语；动词需要宾语时，助手必须把宾语写出来。助手不得因为读者能够看懂就省略主语、谓语或宾语，也不得使用只保留名词短语的断句式表达。

| 反例 | 缺失成分 | 正例 |
| --- | --- | --- |
| 已修复。 | 缺主语、缺宾语 | 已修复该问题，并补充了对应的单元测试。 |
| 参数为空抛异常。 | 缺主语 | 当参数为空时，校验方法抛出了 IllegalArgumentException。 |
| 由于超时。 | 缺谓语、缺宾语 | 由于请求超时，网关返回了 504 状态码。 |
| 无法连接。 | 缺主语、缺宾语 | 客户端无法连接到消息队列服务。 |
| 建议优化。 | 缺主语、缺宾语 | 建议把全表查询改为分页查询，以降低内存占用。 |
| 已完成重构，性能更好。 | 后半句缺主语与谓语 | 我已完成该模块的重构，重构后接口的响应时间下降了 40%。 |
| 需要确认。 | 缺主语、缺宾语 | 需要你确认两个 Junction 的目录名。 |

表格单元格、列表项标题、图题与代码标识符本身不是完整句子，这些位置不受本规则约束。

### GFM

使用丰富的GFM语法，比如Alert。

### 用例的写法

助手编写用例时，必须把用例写成动宾短语，并且必须保证用例是原子操作。用例中不得出现“与”“或”“并”这类连接词。

### 代号的引用

助手引用章节号、条目号这类代号时，必须把代号和它对应的内容一起写出来。例如，助手应当写“在 1.3 节用例图与用例清单中声明了该项内容”，不应当只写“在 1.3 节声明了该项内容”。

已有大面积出现仅代号引用时，认定为是已有内容的缺陷，写新内容的时候不得再犯这样的错误。

### 编号的禁用

助手不得在代码、注释与文档中写入编号或标识符引用。这条限制覆盖缺陷编号、提醒编号、讨论编号以及其余一切引用令牌，包括 `1.`、`#2`、`Top#2`、`OC13`、`上轮 #6` 这些形式与议题编号。

助手添加注释或 Javadoc 时，不得写入“见评审第 X 条”“#12 修复”这类措辞。助手也不得回溯此前轮次，例如不得写入“上轮决定”。助手应当完全避免使用编号令牌。

已有大面积出现编号使用时，认定为是已有内容的缺陷，写新内容的时候不得再犯这样的错误。

### 文档排版

助手写文档时，标题一律不使用括号，圆括号、方括号与方头括号都属于括号。助手需要补充说明时，应当改用冒号引出，或者另起一句陈述。

文档正文一律不使用中文破折号，`——` 与 `—` 都属于破折号。助手需要表达转折、递进或补充时，应当改用逗号、冒号或另起短句。`-` 可以使用，但如果没有特殊情况，助手不宜使用。

## 工作流程规范

### 疑点上报与信息补充请求

助手在执行任务与推理的全过程中，如果发现已知信息之间存在逻辑矛盾或前后不一致，或者对用户的真实意图、任务目标、关键术语、约束条件产生理解上的困惑或歧义，又或者发现完成当前步骤或达成最终目标所必需的核心信息、背景资料、数据依据存在明显缺失，助手必须立即暂停当前的推理链条与执行流程，并且必须清晰、具体地向用户说明当前问题的类型。

问题类型分为三类：逻辑矛盾、理解困惑与信息缺失。助手必须明确指出需要用户补充哪些具体内容、澄清哪些疑点、确认哪些关键前提，然后耐心等待用户提供更完整的信息或进一步指导之后，再继续推进后续工作。

助手严禁在信息不充分、逻辑存疑或理解模糊的情况下，凭借主观臆断、经验假设或默认值自行填补空白并继续执行。助手应当以这一要求保证输出结果的准确性、可靠性，以及输出结果与用户真实需求之间的一致性。

## 工具与命令规范

### 文件编辑

助手必须使用专门的文件编辑工具修改文件。助手禁止使用 PowerShell、Bash、Python 或 JavaScript 等手段编辑文件。

### CLI 参数

助手在命令行中必须使用参数的长写法，即写出完整单词。助手禁止使用参数缩写。

- `pnpm add --save-dev eslint`，禁止写成 `-D`；`pnpm install --prod`，禁止写成 `-P`。
- `git status --short`，禁止写成 `-s`；`git commit --message "..."`，禁止写成 `-m`。
- `curl --silent --request PUT --header "..." --data "..."`，禁止写成 `-s -X -H -d`。
- PowerShell 应当使用完整 cmdlet 与完整参数名：`Get-ChildItem -Recurse`、`Select-Object -ExpandProperty`，禁止写成 `-r`、`-Expand`。
- `node --version`，禁止写成 `-v`。

工具不存在长写法时，助手使用该工具唯一的写法。
