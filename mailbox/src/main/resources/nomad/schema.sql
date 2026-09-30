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
create table if not exists identity_bundle (
    uid text primary key,
    sig_key bytea not null,
    ik_dh bytea not null,
    sig_ik_dh bytea not null,
    spk_id int not null,
    spk bytea not null,
    sig_spk bytea not null,
    updated_at timestamptz not null default now()
);
create table if not exists one_time_prekey (
    uid text not null,
    opk_id int not null,
    pub bytea not null,
    primary key (uid, opk_id)
);
