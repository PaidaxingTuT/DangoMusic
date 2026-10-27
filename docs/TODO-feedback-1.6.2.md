# TODO（用户反馈，暂不修复）

> 2026-10-27：以下问题用户已确认，**明确要求今天先不修复，仅记录**。按 CLAUDE.md 第 1 条，不主动改动。
>
> 用户原话：「歌单封面获取不到，首页推荐数据太少，歌单也要改成按时间排序，越晚添加的越在上面，
> 今天时间来不及了，先不要修复，记住就好」。同时确认 v1.6.2 已可正常使用。
> 修复前需重新确认排序需求与封面字段来源。

## 1. 歌单封面获取不到（酷狗）

- 现象：侧栏 / 首页歌单墙的封面拿不到，显示占位图。
- 位置：`common/core/src/main/kotlin/me/sorihiko/dango/music/BaseKugouApi.kt` → `getUserPlaylists()`
  封面来源目前只取 `pic`：`coverUrl(o.text("pic"))`。
- 待查：`/v7/get_all_list` 返回条目里实际可用的封面字段（酷狗常见 `pic` / `img` / `sizable_cover` /
  `trans_param.union_cover`，可能带 `{size}` 占位符、可能是 http 或相对路径）。
  当前 `coverUrl()` 只做 `{size}`→400 与 http→https 替换，没有其它字段回退。
- 注意：`CoverCache.get()` 的 key 已包含平台+歌单来源+URL，所以这次是**URL 本身为空**，不是缓存串图。

## 2. 首页推荐歌单数量太少（酷狗）

- 现象：首页推荐只有很少几个歌单。
- 位置：`BaseKugouApi.recommendPlaylists()` 只解析 `data.info.special` 这一个数组；
  同一次请求的 `data.info` 里还有 `custom_special`、`recommend`、`rank`、`album`、`magazine`、
  `topic` 等分组（1.6.2 实测确认存在）。
- 待确认：用户希望"更多"是指把上面这些分组也一起合并进首页，还是只扩大 `special` 的取用量
  （例如改用 `/v2/special_recommend` 或 `yueku` 的 `type=11` 变体）。

## 3. 我的歌单要按时间倒序（越晚添加的越在上面）

- 现象：酷狗"我的歌单"顺序不是按添加时间倒序。
- 位置：`BaseKugouApi.getUserPlaylists()` 目前**完全没有排序**，直接用接口返回顺序。
- 依赖：需要先在接口返回里确认时间字段名（`add_time` / `create_time` / `user_create_time` /
  `list_create_time`，单位秒或毫秒）。当前代码没有读取任何时间字段，`NeteasePlaylist`
  也没有保存时间的属性——排序前需要先确认字段，并决定是否给 `NeteasePlaylist` 加时间字段。
- 范围：仅酷狗需要，还是网易云也要一起改（网易云 `/api/user/playlist` 也没有按时间排）。
  需先问用户，避免顺手改到网易云。

## 修复时必须先确认

1. 封面：哪个字段是用户实际想要的（`pic` 拿不到时回退顺序）。
2. 推荐：是"合并其它分组"还是"换接口/加大 pagesize"。
3. 排序：时间字段名 + 单位，以及是否只改酷狗。
4. 排序要改在哪里：接口层排序，还是 UI 层排序（网易云会受影响，需用户确认）。
