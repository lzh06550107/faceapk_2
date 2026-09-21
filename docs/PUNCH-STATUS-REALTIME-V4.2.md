# 打卡面板状态实时一致性 V4.2

## 问题

打卡页面状态面板存在“顶部当前状态”和“最近状态历史”不一致：例如历史最新记录已是“后台人脸库更新完成”，顶部仍停留在“正在初始化打卡环境...”。

根因在现有统一状态中心 `PunchApplication`：`reportStatusEvent()` 原先只追加历史（`keepAsCurrent=false`），不会同步更新 `punchDataStatus/currentLevel`。因此 Listener 虽然实时收到新快照，快照中的 `currentStatus` 仍是旧值。

## 修复

1. `reportStatusEvent()` 改为同时更新 current 和 history，用户可见状态只有一个真值源。
2. 每次状态发布增加单调 `sequence`，`PunchStatusSnapshot` 和 `PunchStatusEntry` 都携带序号。
3. `PunchFragment` 记录 `lastRenderedPunchStatusSequence`，忽略序号更小的异步旧快照，避免跨线程回调乱序把新状态覆盖回旧状态。
4. 连续发布相同 message/level 时刷新历史第一项，而不是保留旧时间戳，保证顶部 current 与历史第一项对应同一次最新发布。
5. 沿用现有 `PunchApplication.PunchStatusListener`；没有新增第二套状态中心，也没有轮询 UI。

## 预期效果

当后台人脸库更新过程发布：

```text
正在后台增量更新人脸库...
后台人脸更新 120/600
后台人脸更新 121/600
...
后台人脸库更新完成
```

顶部状态会随发布实时变化，最后立即变成：

```text
已就绪  后台人脸库更新完成
```

历史第一条同时为“后台人脸库更新完成”。

## 不在本轮范围

- FaceSdkScheduler / FaceSdkOperationGuard
- FaceSearch 并发策略
- V4.1 Runtime Library Availability
- NTP 配置
- 打卡业务条件、稳定匹配 1500ms
- 状态面板视觉样式

## 真机验收

1. 打开打卡页并展开状态面板。
2. 触发员工/人脸后台同步。
3. 观察顶部状态与历史第一项；每次用户可见状态发布后两者应同步更新。
4. 最终出现“后台人脸库更新完成”后，顶部必须立即显示同一文案，等级标签为“已就绪”。
5. 切换页面再返回，顶部仍应读取 `PunchApplication.getPunchStatusSnapshot()` 显示最新状态。
