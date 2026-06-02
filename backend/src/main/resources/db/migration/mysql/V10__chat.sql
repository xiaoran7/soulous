-- 【AI 拆解对话（Gemini 式重构）新表：分类 / 对话 / 消息】
-- 取代旧的目标中心模型（goal + planning_session，旧表暂留不动）。
-- chat_message.content 用 MEDIUMTEXT：上传附件的提取文本（上限 3w 字）会拼进用户消息，
-- 单条可能超过 MySQL TEXT 的 ~64KB 上限。

create table chat_category (
    id bigint not null auto_increment,
    user_id bigint not null,
    name varchar(60) not null,
    sort_order integer not null,
    created_at datetime(6),
    primary key (id)
) engine=InnoDB;

create table chat_conversation (
    id bigint not null auto_increment,
    user_id bigint not null,
    category_id bigint,
    title varchar(200) not null,
    pending_plan_json TEXT,
    running_summary TEXT,
    summarized_up_to_idx integer not null,
    turn_count integer not null,
    created_at datetime(6),
    last_activity_at datetime(6),
    primary key (id)
) engine=InnoDB;

create table chat_message (
    id bigint not null auto_increment,
    conversation_id bigint not null,
    idx integer not null,
    role enum ('ASSISTANT','USER') not null,
    content MEDIUMTEXT,
    created_at datetime(6),
    primary key (id)
) engine=InnoDB;

alter table chat_category
    add constraint fk_chat_category_user foreign key (user_id) references user_account (id);
alter table chat_conversation
    add constraint fk_chat_conv_user foreign key (user_id) references user_account (id);
alter table chat_conversation
    add constraint fk_chat_conv_category foreign key (category_id) references chat_category (id);
alter table chat_message
    add constraint fk_chat_msg_conv foreign key (conversation_id) references chat_conversation (id);

create index idx_chat_category_user on chat_category (user_id);
create index idx_chat_conv_user on chat_conversation (user_id);
create index idx_chat_conv_category on chat_conversation (category_id);
create index idx_chat_msg_conv on chat_message (conversation_id);
