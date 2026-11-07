-- 每日活动缩略图表（2026-11-07 新增，Zhang 要求）
-- 用法：在 Supabase 项目的 SQL Editor 里运行本文件一次即可。
--
-- 一行 = 一天的活动拼图：日期、活动说明（各时段一句话）、统计和拼图图片本身。
-- 图片以 base64 文本存（拼图约 1 MB，base64 后约 1.4 MB/天）。
-- 写入：每日拼图任务用 service_role key 走 PostgREST upsert（绕过 RLS）。
-- 读取：手机 app 用 publishable key 只读，所以给 anon 一个 select 授权。

create table if not exists public.daily_sheets (
  day           date primary key,  -- 日期（Asia/Shanghai）
  caption_text  text not null default '',  -- 每日活动说明（拼图任务生成，各活动时段一句话）
  photo_count   integer,           -- 当天快照总数
  usable_count  integer,           -- 过滤后可用张数
  cluster_count integer,           -- 活动时段数
  sheet_size_kb integer,           -- 拼图大小（KB）
  image_base64  text not null,     -- 拼图 JPEG 的 base64
  updated_at    timestamptz not null default now()
);

comment on table public.daily_sheets is '每日活动缩略图：每天一行，含活动说明与拼图 base64';

grant select on public.daily_sheets to anon;
