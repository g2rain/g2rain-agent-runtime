# R02 · 契约格式版本与动作语义版本标记改名

## 目标

1. 执行契约在根属性中声明格式版本；Runtime 只运行自己能理解的版本，不兼容时本轮直接失败，并明确告诉用户该升级契约还是升级运行时。
2. `task_instance.skill_versions` 改名为 `spec_pins`，名称与含义一致。

## 依据

- Studio：`scripts/g2rain-agent-studio.sql`（种子中的 `skill_contract`）
- Runtime：`engine.flow.hydrate.DefaultBundleLoader`、`ContractValidator`、`RuntimeErrorCode`、`domain.state.task.TaskMetadata`、`engine.state.repo.TaskRepository`、`engine.flow.hydrate.DefaultHydrator`、`docs/STATE_SPEC.md`、`docs/CONTRACT_SPEC.md`

## 一、契约格式版本

### 版本规则

- 版本属于契约本身：契约 JSON 的根属性 `version`（必填，字符串，格式 `主版本.次版本`，两段均为非负整数），表示这份契约按哪个版本的格式编写。第一版为 `1.0`。
- 合法取值以 `docs/CONTRACT_SPEC.md` 的「版本历史」表为准；Runtime 修改契约格式时，在同一次提交中修改 `ContractVersion.CURRENT` 并在版本历史中加一行，写明变化与迁移方式。
- 不兼容的修改（字段改名、删除、含义改变）升主版本：`1.x` → `2.0`；兼容的新增（新增可选字段）升次版本：`1.0` → `1.1`。
- Runtime 接受一个契约，当且仅当：契约主版本 = Runtime 主版本，且契约次版本 ≤ Runtime 次版本。缺少 `version` 或格式非法不做猜测，按不支持处理。
- Runtime 只支持一个主版本；主版本升级时契约数据整体迁移，不保留旧主版本的处理代码。

### Studio（`g2rain-agent-studio`）

不改。Studio 只存储、返回契约 JSON，不理解契约格式。已按旧方案做的改动全部撤回：`skill_profile.contract_version` 列、`StudioProperties`、`application.yml` 中的配置、DTO/PO/VO/Mapper 中的 `contractVersion`、`AgentBundleVo` 中的 `contractVersion`、相关测试。`scripts/g2rain-agent-studio.sql` 与 deploy 副本恢复原样后，仅按下文「种子」一节修改契约内容。

### Runtime（`g2rain-agent-runtime`）

1. `ContractVersion`（已有）：`CURRENT = "1.0"`；Javadoc 中「版本串来源」改为契约根属性 `version`，其余四点保留；新增判断结果的枚举（如 `Verdict`：`ACCEPTED`、`INVALID`、`OUTDATED`、`AHEAD`），`accepts` 改为返回该枚举的方法（如 `check`）：
   - `INVALID`：缺少或格式非法；
   - `OUTDATED`：契约主版本低于 Runtime 主版本（需要升级契约）；
   - `AHEAD`：契约主版本高于 Runtime 主版本，或主版本相同而次版本更高（需要升级 Runtime）。
2. 检查只在一处：`DefaultBundleLoader` 解析契约时，先读根属性 `version` 并检查；不通过即抛对应错误码，本轮失败（不跳过该技能）。
3. `RuntimeErrorCode`：
   - `CONTRACT_VERSION_INVALID("agent_runtime.42261")`：「技能「{0:skillKey}」的执行契约缺少有效的版本号，请按当前格式（{1:current}）补充」
   - `CONTRACT_VERSION_OUTDATED("agent_runtime.42262")`：「技能「{0:skillKey}」的执行契约版本 {1:version} 已过时，当前运行时要求 {2:current}，请按新格式升级执行契约」
   - `CONTRACT_VERSION_AHEAD("agent_runtime.42263")`：「技能「{0:skillKey}」的执行契约版本 {1:version} 高于当前运行时支持的 {2:current}，请升级运行时」

   `g2rain-infra/scripts/g2rain-infra.sql` 中已追加的 42261 三行原地改为新文案（尚未提交，不另加行），42262、42263 追加在末尾；同步 deploy。
4. `docs/CONTRACT_SPEC.md`：根属性表新增 `version`；「契约格式版本」一节改为根属性写法，并新增「版本历史」表（当前一行：`1.0`，初始格式）；全部示例契约补上 `"version": "1.0"`。
5. 测试与示例中的契约 JSON 一律补上 `"version": "1.0"`。

### 种子

- `g2rain-agent-studio/scripts/g2rain-agent-studio.sql` 中现有技能的 `skill_contract` 在根上补 `"version": "1.0"`（原地修改内容，不改 id）；同步 deploy。
- 测试环境执行：`UPDATE skill_profile SET skill_contract = JSON_SET(skill_contract, '$.version', '1.0') WHERE skill_contract IS NOT NULL AND delete_flag = 0;`

## 二、`skill_versions` 改名为 `spec_pins`

该列存的是「技能键 → 动作语义版本标记（pin）」，开任务时锁定、续跑沿用，不是技能本身的版本。

1. 表：`task_instance.skill_versions` 改为 `spec_pins JSON DEFAULT NULL COMMENT '开任务时锁定的动作语义版本标记（技能键 → 版本标记）'`；同步 `g2rain-deploy`。
2. 手工改 PO、DTO、SelectDto、VO、Mapper XML 中的字段与列名；`TaskMetadata`、`TaskRepository`、`DefaultHydrator` 中的 `skillVersions` 改为 `specPins`；相关 Javadoc 与注释统一为「动作语义版本标记」。
3. 测试：`TaskRepositoryTest`、`DefaultHydratorTest` 同步改名。
4. 文档：`docs/STATE_SPEC.md` 同步。已完成的修复记录与审计文档描述的是当时的状态，不改。

## 不做

- 不写任何兼容旧名、旧格式的代码。
- 不改 `g2rain-bootstrap`。

## 验收

- `ContractVersionTest`：`1.0` 接受；在 `CURRENT = 1.0` 时 `1.1`、`2.0` 为 `AHEAD`；`0.9` 为 `OUTDATED`；空、`1`、`1.0.0`、`a.b`、超长数字为 `INVALID`。
- `DefaultBundleLoaderTest`（或同等测试）：三种情况分别抛对应错误码，参数为技能键、版本、当前版本。
- Studio：相对改动前无代码差异，仅种子 SQL 中契约补了 `version`。
- 改名后全工程搜索 `skillVersions`、`skill_versions`，除已完成的历史记录文档与 basis 中由导入工具生成的接口文档外无命中。
- Runtime `mvn clean verify` 通过。

## 测试环境 SQL

```sql
-- g2rain_agent_studio：契约补版本号
UPDATE `skill_profile` SET `skill_contract` = JSON_SET(`skill_contract`, '$.version', '1.0')
 WHERE `skill_contract` IS NOT NULL AND `delete_flag` = 0;

-- g2rain_agent_runtime
ALTER TABLE `task_instance`
  RENAME COLUMN `skill_versions` TO `spec_pins`;
ALTER TABLE `task_instance`
  MODIFY COLUMN `spec_pins` JSON DEFAULT NULL COMMENT '开任务时锁定的动作语义版本标记（技能键 → 版本标记）';
```
