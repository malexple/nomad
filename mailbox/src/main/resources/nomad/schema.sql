create table if not exists node_epoch (
    id int primary key check (id = 1),
    epoch bigint not null
);
insert into node_epoch (id, epoch) values (1, 1) on conflict do nothing;
create table if not exists envelope (
    seq bigserial primary key,
    envelope_id uuid not null unique,
    version smallint not null,
    mailbox_id text not null,
    ciphertext bytea not null,
    expiry_day bigint not null,
    created_at timestamptz not null default now()
);
create index if not exists envelope_mailbox_seq on envelope (mailbox_id, seq);
