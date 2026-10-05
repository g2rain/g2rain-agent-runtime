# Skill 编写指南

本指南说明如何为一个业务系统编写一套可以直接加载运行的数字员工与 Skill 配置。它既供开发人员使用，也可以直接交给 Codex、Claude Code 等编码代理执行：按第 1 节准备输入，按第 3 节的步骤生成，最后用第 7 节的清单自检。

属性的完整定义见 [SKILL_CONTRACT_SPEC.md](CONTRACT_SPEC.md)。本指南中的规则都以该规范为依据。

---

## 目录

1. [输入与产出](#1-输入与产出)
2. [给编码代理的任务说明](#2-给编码代理的任务说明)
3. [编写步骤](#3-编写步骤)
4. [编写规则](#4-编写规则)
5. [契约片段模板](#5-契约片段模板)
6. [完整示例](#6-完整示例)
7. [自检清单](#7-自检清单)
8. [常见错误](#8-常见错误)

---

## 1. 输入与产出

### 1.1 需要准备的输入

| 输入 | 内容 | 用途 |
|---|---|---|
| 业务域说明 | 系统管理哪些业务对象、对象之间的关系、哪些操作是高影响操作 | 划分 Skill、写数字员工画像、写业务规则 |
| MCP 工具清单 | 每个工具的 `name`、`description`、`inputSchema`（参数名、类型、必填） | 写 `toolNames` 和 `tools.<t>.args` |
| 响应格式 | 统一响应包装的结构，例如 `{status, errorCode, errorMessage, data}`；列表、分页、详情各自的 `data` 结构 | 写 `success`、`error`、`extracts` |
| 机构 | `organ_id`、资源类型（`PLATFORM` 平台级 / `CUSTOM` 租户级） | 写 SQL |

g2rain 服务的统一响应包装为：

```json
{"status": 200, "errorCode": null, "errorMessage": null, "data": ...}
```

### 1.2 产出物

一份 SQL 脚本，向 agent-studio 的四张表插入数据：

| 表 | 每行代表 |
|---|---|
| `agent_profile` | 一个数字员工 |
| `skill_profile` | 一个技能，包含 `semantic_desc`、`skill_manual`、`skill_contract` |
| `agent_skill_binding` | 数字员工拥有某个技能 |
| `skill_tool_binding` | 技能可以调用某个业务工具（`toolNames`） |

---

## 2. 给编码代理的任务说明

把下面这段与第 1 节的输入一起交给编码代理即可：

```text
请按 docs/SKILL_AUTHORING_GUIDE.md 为以下业务系统编写数字员工与 Skill 配置，产出一份可执行的 SQL 脚本。

要求：
1. 严格遵循 docs/SKILL_CONTRACT_SPEC.md 中的属性定义和第 4 节"编写规则"。
2. 每个 skill_contract 必须是合法 JSON，不写注释。
3. 每个工具的 args 只包含该工具 inputSchema 中真实存在、且需要约束来源的参数。
4. 完成后按第 7 节"自检清单"逐项检查，并在 SQL 末尾以注释列出检查结果。

业务域说明：<粘贴>
MCP 工具清单：<粘贴>
响应格式：<粘贴>
organ_id：<填写>，resource_type：<PLATFORM 或 CUSTOM>
```

---

## 3. 编写步骤

### 步骤 1：划分 Skill

| 原则 | 说明 |
|---|---|
| 按业务对象和生命周期划分 | 例如"文章生命周期""标签管理""站点与栏目"，不要一个接口一个 Skill |
| 大小适中 | 每个 Skill 一般包含 3～15 个工具 |
| 前置查询可以共享 | 一个 Skill 需要先定位其他对象（例如打标签前要找到文章）时，把对应的只读查询工具也加入该 Skill 的 `toolNames` |
| 纯查询能力可以委派 | 只包含只读工具、会被其他 Skill 复用的查询能力，可以单独成为 `delegatable: true` 的 Skill |

### 步骤 2：写数字员工画像（agent_profile）

| 字段 | 写法 |
|---|---|
| `identity_desc` | 一句话：你是谁、负责什么领域 |
| `mission_desc` | 帮用户达成哪些业务目标。路由时用它选择数字员工，要具体 |
| `boundary_desc` | 不处理什么；哪些操作需要确认；不承诺系统不具备的能力 |
| `style_desc` | 语气与表达要求 |
| `principle_desc` | 行为原则，例如"不编造业务结果" |

### 步骤 3：写 semantic_desc

格式：**负责……。适用于……。不负责……。**

示例：

> 负责 CMS 标签库及文章标签关系管理。适用于创建、查询、删除标签，以及为文章添加、查询、解除标签。不负责文章正文、发布状态或网站导航。

### 步骤 4：写 skill_manual

使用固定结构：

```markdown
## 目标
一两句话说明本技能完成什么。

## 业务规则
- 前置条件：做某事之前必须先具备什么、如何获得（查询哪个工具、或向用户确认）。
- 定位规则：按名称查找时，唯一结果直接使用，多个结果用 builtin_node_follow_up 请用户选择（把候选放进 suggestions）。
- 确认规则：哪些写操作会触发确认（WRITE 工具默认都需要确认，调用后按提示使用 builtin_node_approval）。
- 默认值：未明确时采用的默认取值。
- 边界：不处理的事项交给哪个技能。
- 不得编造任何业务主键或业务结果，以工具实际返回为准。

## 前置与产出
- 需要的输入。
- 完成后应产生的业务结果（ID、状态）。
```

规则：

- 只写业务规则，不写工具调用的技术细节（参数来源、重试由契约保证）。
- 写操作的确认由 Runtime 强制执行，手册中只需说明哪些操作属于高影响操作。

### 步骤 5：写 skill_contract

按以下顺序编写：

| 顺序 | 内容 | 依据 |
|---|---|---|
| 1 | 为 `toolNames` 中的每个工具建立 `tools.<t>`，确定 `sideEffect`、`label` | 工具语义 |
| 2 | 为每个工具写 `success`、`error` | 响应格式 |
| 3 | 为查询工具写 `extracts`（`key` 全局唯一、`path` 指向数据、需要时加 `entity`） | 响应中的 `data` 结构 |
| 4 | 为需要约束来源的参数写 `args` | 第 4.3 节 |
| 5 | 为写工具写 `idempotencyKey`，需要时写 `verify` | 第 4.4 节 |
| 6 | 需要向下游或后续任务传值时，写 `outputs`（含 `share`）和对应的 `inputs` | 第 4.5 节 |
| 7 | 写 `doneWhen` | 第 4.6 节 |
| 8 | 确定 `mode`、`delegatable` | 第 4.1 节 |

### 步骤 6：生成 SQL

按 [第 6.3 节](#63-sql) 的格式生成四张表的插入语句，ID 自行规划不冲突的区间。

---

## 4. 编写规则

规则分为三级：**必须**（违反会导致加载失败或运行出错）、**应当**（违反会导致行为不符合预期）、**禁止**。

### 4.1 根属性

| # | 级别 | 规则 |
|---|---|---|
| R1 | 必须 | `skill_contract` 是合法 JSON；枚举值使用规范中的大写写法 |
| R2 | 应当 | `mode` 使用 `REACT` |
| R3 | 必须 | `delegatable: true` 的 Skill 中不能有 `WRITE` 工具 |
| R4 | 应当 | `toolNames` 中的每个业务工具都在 `tools` 中配置 |
| R5 | 禁止 | 把内置工具（`builtin_*`）写进 `toolNames` 或 `tools` |

### 4.2 工具

| # | 级别 | 规则 |
|---|---|---|
| T1 | 必须 | 有副作用的工具（新增、修改、删除、状态变更）设为 `"sideEffect": "WRITE"`；查询设为 `"NONE"` |
| T2 | 必须 | `WRITE` 工具配置 `success` |
| T3 | 应当 | 每个工具都配置 `success` 和 `error`，否则 Runtime 不做业务判定 |
| T4 | 应当 | 每个工具都配置 `label`，用作给用户的过程提示，写成动作短语，如"查询标签列表" |
| T5 | 禁止 | 在 `NONE` 工具上写 `"confirm": true` |
| T6 | 应当 | 查询工具配置 `extracts`，`key` 在整个 Skill 内唯一 |

### 4.3 参数

| # | 级别 | 规则 |
|---|---|---|
| A1 | 必须 | `args` 只写该工具 inputSchema 中真实存在的参数。**不要把技能内所有参数复制到每个工具上** |
| A2 | 必须 | 机构、用户等身份参数使用 `from: "platform.<字段>"`，并配 `missing: {"action": "FAIL", "message": "..."}` |
| A3 | 必须 | 选填的参数（如查询过滤条件）写 `"required": false`，或者不列入 `args`。`required` 默认为 `true` |
| A4 | 应当 | 业务主键（ID）参数写 `"sources": ["EVIDENCE"]` 或 `["USER", "EVIDENCE"]`，防止模型编造 ID |
| A5 | 应当 | 可以由模型根据用户意图自由填写的参数（标题、正文、名称等），**不列入 `args`**，或写 `"sources": ["USER", "MODEL"]` |
| A6 | 必须 | `missing` 只写在必填参数上；`missing.action = CALL_TOOL` 时，`tool` 必须在 `toolNames` 中，并且**不能是参数所属的工具本身** |
| A7 | 禁止 | `from` 与 `const` 同时出现 |
| A8 | 禁止 | `from` + `sources: ["USER", ...]` 但不含 `MODEL`。绑定失败时，用户回答也会被判定为模型编造，导致反复追问。需要回退时写 `from` + `"sources": ["MODEL"]` |
| A9 | 应当 | 数组中的 ID 用 `[]` 路径逐项校验，例如 `"tagIds[]": {"sources": ["EVIDENCE"]}` |
| A10 | 应当 | 敏感参数（证件号、手机号等）写 `"sensitive": true` |
| A11 | 应当 | 会出现在审批文案中的参数写 `label`，例如 `"label": "文章标题"` |
| A12 | 禁止 | 在 `args.*.from` 中使用 `prior.`（键需要具体实体 ID，编写时无法确定）；需要前情时使用 `inputs.*.from: "prior.<type>"` |
| A13 | 禁止 | 使用 `from: "shared.*"` / `"session.*"` 却没有任何 Skill 通过 `outputs.*.share` 写入该键 |

### 4.4 写工具

| # | 级别 | 规则 |
|---|---|---|
| W1 | 应当 | 配置 `idempotencyKey`，由能表达"同一业务动作"的参数组成。例如：创建文章 `["spaceId", "title"]`，打标签 `["articleId"]`，删除 `["id"]` |
| W2 | 应当 | 能回查结果的写工具配置 `verify`：回查工具必须是同一 Skill 中的 `NONE` 工具，参数用 `from: "call.<参数>"` 引用原请求；回查工具的 `success` 必须能在"查不到"时判为失败 |
| W3 | 应当 | 不需要写 `"confirm": true`（`WRITE` 默认需要确认）。确需免确认的，写 `"confirm": false` 并在部署配置 `state.write-confirm-bypass-tools` 中加入工具名 |

### 4.5 输入、产出与共享

| # | 级别 | 规则 |
|---|---|---|
| O1 | 应当 | 只有需要传给下游节点或后续任务的值才声明为 `outputs` |
| O2 | 必须 | 声明的产出若不是每次都会写入，写 `"required": false`（默认 `true`，未写入时节点无法完成） |
| O3 | 必须 | 产出的 key 不能与平台字段同名：`userId`、`memberId`、`name`、`organId`、`organName`、`organType`、`deptPath` |
| O4 | 应当 | 跨技能传值使用 `outputs.<k>.share`（`TASK` 或 `SESSION`），接收方用 `inputs.<k>.from: "shared.<k>"` 或 `"session.<k>"` |
| O5 | 应当 | `outputs.<k>.sources` 写 `["EVIDENCE"]` 或 `["EVIDENCE", "USER"]`，保证产出来自真实结果 |
| O6 | 应当 | 业务对象需要在后续任务中引用时，在查询工具的 `extracts` 上配置 `entity: {"type": "...", "id": "id", "name": "<名称字段>"}` |

### 4.6 完成条件

| # | 级别 | 规则 |
|---|---|---|
| D1 | 必须 | `doneWhen` 只使用规范中的条件形式 |
| D2 | 必须 | 查询工具出现在 `doneWhen` 中时，写成 `<tool> succeeded OR <tool> empty`。只写 `succeeded` 时，查询结果为空会导致节点无法完成 |
| D3 | 应当 | 多用途 Skill 的 `doneWhen` 为各类完成动作的 `OR` 组合：每种写操作成功、以及作为最终目的的查询成功或为空 |
| D4 | 应当 | 单一目的 Skill 使用 `target` + `"doneWhen": "target succeeded"` |
| D5 | 禁止 | 在 `doneWhen` 中使用括号；`AND` 的优先级高于 `OR` |

---

## 5. 契约片段模板

以下模板基于 g2rain 的响应包装 `{status, errorCode, errorMessage, data}`。

### 5.1 通用的成功与错误

```json
"success": {"path": "status", "op": "EQ", "value": 200},
"error": {"code": "errorCode", "message": "errorMessage"}
```

### 5.2 查询列表

```json
"get_tag_list": {
  "label": "查询标签列表",
  "sideEffect": "NONE",
  "args": {
    "organId": {
      "from": "platform.organId",
      "missing": {"action": "FAIL", "message": "缺少机构上下文"}
    },
    "tagName": {"required": false, "sources": ["USER", "MODEL"]}
  },
  "success": {"path": "status", "op": "EQ", "value": 200},
  "error": {"code": "errorCode", "message": "errorMessage"},
  "extracts": [
    {"key": "tag_list", "path": "data", "fields": ["id", "tagName"],
     "entity": {"type": "tag", "id": "id", "name": "tagName"}}
  ]
}
```

### 5.3 查询分页

```json
"extracts": [
  {"key": "article_page", "path": "data.records", "fields": ["id", "title", "status"]}
]
```

### 5.4 创建

```json
"post_tag_save": {
  "label": "创建标签",
  "sideEffect": "WRITE",
  "idempotencyKey": ["tagName"],
  "args": {
    "organId": {
      "from": "platform.organId",
      "missing": {"action": "FAIL", "message": "缺少机构上下文"}
    },
    "tagName": {"sources": ["USER", "MODEL"], "label": "标签名称"}
  },
  "success": {"path": "status", "op": "EQ", "value": 200},
  "error": {"code": "errorCode", "message": "errorMessage"},
  "extracts": [{"key": "tag_saved", "path": "data"}]
}
```

### 5.5 按 ID 操作（修改状态、删除）

```json
"delete_tag_id": {
  "label": "删除标签",
  "sideEffect": "WRITE",
  "idempotencyKey": ["id"],
  "args": {
    "id": {
      "sources": ["EVIDENCE"],
      "missing": {"action": "CALL_TOOL", "tool": "get_tag_list"},
      "label": "标签 ID"
    }
  },
  "success": {"path": "status", "op": "EQ", "value": 200},
  "error": {"code": "errorCode", "message": "errorMessage"}
}
```

### 5.6 写操作回查

```json
"post_article_save": {
  "sideEffect": "WRITE",
  "idempotencyKey": ["spaceId", "title"],
  "verify": {
    "tool": "get_article_list",
    "args": {
      "spaceId": {"from": "call.spaceId"},
      "title": {"from": "call.title"}
    }
  }
}
```

回查工具 `get_article_list` 的 `success` 需要在查不到时判为失败，例如：

```json
"success": {"path": "data", "op": "TRUTHY"}
```

（`TRUTHY` 对空数组判为 false。）

### 5.7 跨技能传值

提供方 Skill：

```json
"outputs": {
  "spaceId": {
    "type": "VALUE",
    "required": false,
    "share": "TASK",
    "sources": ["EVIDENCE"],
    "hint": "目标内容空间 ID"
  }
}
```

使用方 Skill：

```json
"inputs": {
  "spaceId": {"from": "shared.spaceId", "required": false, "provider": "cms.structure.manage"}
},
"tools": {
  "post_article_save": {
    "args": {
      "spaceId": {"from": "inputs.spaceId", "sources": ["MODEL"], "label": "内容空间"}
    }
  }
}
```

`inputs.spaceId` 取到值时参数被自动绑定；取不到时允许模型填写（例如模型先查询空间得到 ID）。

---

## 6. 完整示例

以 CMS 的"标签与文章打标"为例。

### 6.1 工具

| 工具 | 类型 | 参数 | 说明 |
|---|---|---|---|
| `get_tag_list` | 查询 | `organId`、`tagName?` | 标签列表 |
| `post_tag_save` | 写 | `organId`、`tagName` | 创建标签 |
| `delete_tag_id` | 写 | `id` | 删除标签 |
| `get_article_list` | 查询 | `organId`、`title?`、`spaceId?` | 文章列表（用于定位要打标的文章） |
| `post_article_tag_relation_batch_add_tags` | 写 | `articleId`、`tagIds[]` | 为文章批量添加标签 |
| `get_article_tag_relation_list` | 查询 | `articleId?`、`tagId?` | 文章标签关系 |
| `delete_article_tag_relation_id` | 写 | `id` | 解除一条文章标签关系 |

### 6.2 skill_contract

```json
{
  "mode": "REACT",
  "delegatable": false,
  "doneWhen": "post_article_tag_relation_batch_add_tags succeeded OR post_tag_save succeeded OR delete_tag_id succeeded OR delete_article_tag_relation_id succeeded OR get_tag_list succeeded OR get_tag_list empty OR get_article_tag_relation_list succeeded OR get_article_tag_relation_list empty",
  "tools": {
    "get_tag_list": {
      "label": "查询标签",
      "sideEffect": "NONE",
      "args": {
        "organId": {"from": "platform.organId", "missing": {"action": "FAIL", "message": "缺少机构上下文"}},
        "tagName": {"required": false, "sources": ["USER", "MODEL"]}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"},
      "extracts": [
        {"key": "tag_list", "path": "data", "fields": ["id", "tagName"],
         "entity": {"type": "tag", "id": "id", "name": "tagName"}}
      ]
    },
    "post_tag_save": {
      "label": "创建标签",
      "sideEffect": "WRITE",
      "idempotencyKey": ["tagName"],
      "args": {
        "organId": {"from": "platform.organId", "missing": {"action": "FAIL", "message": "缺少机构上下文"}},
        "tagName": {"sources": ["USER", "MODEL"], "label": "标签名称"}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"},
      "extracts": [{"key": "tag_saved", "path": "data"}]
    },
    "delete_tag_id": {
      "label": "删除标签",
      "sideEffect": "WRITE",
      "idempotencyKey": ["id"],
      "args": {
        "id": {"sources": ["EVIDENCE"], "missing": {"action": "CALL_TOOL", "tool": "get_tag_list"}, "label": "标签 ID"}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"}
    },
    "get_article_list": {
      "label": "查找文章",
      "sideEffect": "NONE",
      "args": {
        "organId": {"from": "platform.organId", "missing": {"action": "FAIL", "message": "缺少机构上下文"}},
        "title": {"required": false, "sources": ["USER", "MODEL"]}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"},
      "extracts": [
        {"key": "article_list", "path": "data", "fields": ["id", "title", "status"],
         "entity": {"type": "article", "id": "id", "name": "title"}}
      ]
    },
    "post_article_tag_relation_batch_add_tags": {
      "label": "为文章添加标签",
      "sideEffect": "WRITE",
      "idempotencyKey": ["articleId", "tagIds"],
      "args": {
        "articleId": {"sources": ["USER", "EVIDENCE"], "missing": {"action": "CALL_TOOL", "tool": "get_article_list"}, "label": "文章 ID"},
        "tagIds[]": {"sources": ["EVIDENCE"], "label": "标签 ID"}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"},
      "extracts": [{"key": "article_tags_added", "path": "data"}]
    },
    "get_article_tag_relation_list": {
      "label": "查询文章标签",
      "sideEffect": "NONE",
      "args": {
        "articleId": {"required": false, "sources": ["USER", "EVIDENCE"]},
        "tagId": {"required": false, "sources": ["USER", "EVIDENCE"]}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"},
      "extracts": [{"key": "article_tag_relation_list", "path": "data", "fields": ["id", "articleId", "tagId"]}]
    },
    "delete_article_tag_relation_id": {
      "label": "解除文章标签",
      "sideEffect": "WRITE",
      "idempotencyKey": ["id"],
      "args": {
        "id": {"sources": ["EVIDENCE"], "missing": {"action": "CALL_TOOL", "tool": "get_article_tag_relation_list"}, "label": "关系 ID"}
      },
      "success": {"path": "status", "op": "EQ", "value": 200},
      "error": {"code": "errorCode", "message": "errorMessage"}
    }
  }
}
```

要点说明：

| 位置 | 说明 |
|---|---|
| `organId` | 由平台注入，模型不能修改；缺失时节点直接失败 |
| `tagName`（查询） | 选填过滤条件，`required: false` |
| `tagName`（创建） | 名称由用户提出、模型填写，所以允许 `USER`、`MODEL` |
| `delete_tag_id.id` | 只接受查询结果中的 ID；缺失时要求先调用 `get_tag_list` |
| `articleId` | 用户直接给出的 ID，或文章查询结果中的 ID；缺失时先查文章 |
| `tagIds[]` | 每个标签 ID 都必须来自查询或创建结果 |
| `doneWhen` | 每种写操作成功即完成；查询作为最终目的时，有结果或为空都算完成 |

### 6.3 SQL

```sql
INSERT INTO `skill_profile`
(`id`, `organ_id`, `skill_key`, `skill_name`, `resource_type`, `semantic_desc`, `skill_manual`, `skill_contract`,
 `status`, `description`, `version`, `delete_flag`)
VALUES
(11004, 2, 'cms.tagging.manage', '标签与文章打标', 'PLATFORM',
 '负责 CMS 标签库及文章标签关系管理。适用于创建、查询、删除标签，以及为文章添加、查询、解除标签。不负责文章正文、发布状态或网站导航。',
 '## 目标
维护 CMS 标签，并管理文章与标签之间的关系。

## 业务规则
- 为文章打标前先定位文章：用户给出 ID 直接使用；给出标题时用 get_article_list 查找，唯一结果直接使用，多个结果用 builtin_node_follow_up 请用户选择。
- 目标标签不存在且用户要求使用时，先创建标签，再打标。
- 删除标签、解除标签关系属于高影响操作。
- 不修改文章正文、发布状态或网站导航。
- 不得编造文章 ID、标签 ID，以工具实际返回为准。

## 前置与产出
- 打标需要明确的文章 ID 和标签 ID。
- 完成后应产生可确认的标签 ID 或文章标签关系结果。',
 '<上一节的 JSON，压缩为一行>',
 'ACTIVE', 'CMS 标签打标能力', 0, 0);

INSERT INTO `agent_skill_binding`
(`id`, `organ_id`, `agent_id`, `skill_id`, `enabled_flag`, `description`, `version`, `delete_flag`)
VALUES
(12004, 2, 10001, 11004, 1, NULL, 0, 0);

INSERT INTO `skill_tool_binding`
(`id`, `organ_id`, `skill_id`, `tool_name`, `enabled_flag`, `description`, `version`, `delete_flag`)
VALUES
(13031, 2, 11004, 'get_tag_list', 1, NULL, 0, 0),
(13033, 2, 11004, 'post_tag_save', 1, NULL, 0, 0),
(13034, 2, 11004, 'delete_tag_id', 1, NULL, 0, 0),
(13045, 2, 11004, 'get_article_list', 1, '打标前定位文章', 0, 0),
(13036, 2, 11004, 'post_article_tag_relation_batch_add_tags', 1, NULL, 0, 0),
(13037, 2, 11004, 'get_article_tag_relation_list', 1, NULL, 0, 0),
(13038, 2, 11004, 'delete_article_tag_relation_id', 1, NULL, 0, 0);
```

---

## 7. 自检清单

逐项检查，全部通过后再提交。

**结构**

- [ ] 每个 `skill_contract` 是合法 JSON，没有注释
- [ ] 枚举值全部为规范中的大写写法
- [ ] `toolNames` 中的每个工具都在 `tools` 中配置；`tools` 中没有 `toolNames` 以外的工具
- [ ] 没有把 `builtin_*` 写进 `toolNames` 或 `tools`

**工具**

- [ ] 每个写操作工具 `sideEffect` 为 `WRITE`，并配置了 `success`
- [ ] 每个工具都有 `success`、`error`、`label`
- [ ] 查询工具配置了 `extracts`，`key` 在 Skill 内唯一
- [ ] 写工具配置了 `idempotencyKey`
- [ ] `verify.tool` 是同一 Skill 中的 `NONE` 工具，且在 `toolNames` 中

**参数**

- [ ] 每个工具的 `args` 只包含该工具 inputSchema 中存在的参数
- [ ] 身份参数使用 `from: "platform.*"`
- [ ] 选填参数写了 `"required": false`
- [ ] `missing` 只出现在必填参数上
- [ ] `missing.tool` 在 `toolNames` 中，且不是参数所属的工具本身
- [ ] 没有 `from` + `sources` 含 `USER` 但不含 `MODEL` 的组合
- [ ] 列在 `args` 中且没有 `from`/`const` 的参数，`sources` 非空
- [ ] 没有在 `args` 中使用 `prior.`
- [ ] 所有 `shared.*` / `session.*` 引用都有对应的 `outputs.*.share` 写入

**产出与完成**

- [ ] 产出 key 不与平台字段同名
- [ ] 不一定写入的产出设置了 `"required": false`
- [ ] `doneWhen` 中的查询工具都写成 `succeeded OR empty`
- [ ] `doneWhen` 引用的工具都在 `toolNames` 中
- [ ] `delegatable: true` 的 Skill 中没有 `WRITE` 工具

---

## 8. 常见错误

| 错误写法 | 后果 | 正确写法 |
|---|---|---|
| 每个工具复制同一整块 `args` | 共享值存在时会被强行注入到无关工具；查询会被意外过滤 | 每个工具只写自己的参数 |
| 选填参数配了 `missing` | 不生效，还会误导模型 | 去掉 `missing`，写 `"required": false` |
| `missing.tool` 指向工具本身 | 参数必填时形成"先调用自己"的死循环 | 指向能提供该数据的另一个查询工具 |
| 参数没写 `required`，实际是选填 | 默认必填，缺值时被拦截或追问 | 写 `"required": false` |
| `from: "shared.x"`，但没有任何 `share` 写入 `x` | 永远取不到值 | 在提供方 Skill 的 `outputs.x` 上配置 `share` |
| `from` + `sources: ["USER"]` | 绑定失败时反复追问 | `from` + `sources: ["MODEL"]` |
| `doneWhen: "get_x succeeded"` | 结果为空时无法完成 | `get_x succeeded OR get_x empty` |
| 产出 key 命名为 `name`、`userId` | 写入被拒绝 | 改为 `tagName`、`ownerUserId` 等 |
| 声明了产出但不总是写入，没写 `required: false` | 节点无法完成 | 写 `"required": false` |
| 写工具设 `"confirm": false` 期望免确认 | 仍需确认 | 同时在部署配置 `state.write-confirm-bypass-tools` 中加入工具名 |
| `NONE` 工具写 `"confirm": true` | Skill 加载失败 | 去掉 |
| 写工具没有 `success` | Skill 加载失败 | 补充 `success` |
| `doneWhen` 使用括号或自定义写法 | Skill 加载失败 | 只用规范中的条件形式 |
