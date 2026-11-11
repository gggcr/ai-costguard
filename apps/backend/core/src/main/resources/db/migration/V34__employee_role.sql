-- The role employee (ADR 0051): a person who signs in to «Мой кабинет» and sees only their own data. Users and
-- invitations may carry it. The constraints were unnamed in V4 and V28, so PostgreSQL named them <table>_role_check.
alter table user_account drop constraint user_account_role_check;
alter table user_account add constraint user_account_role_check
    check (role in ('owner', 'finance', 'it_admin', 'agent_owner', 'auditor', 'employee'));

alter table invitation drop constraint invitation_role_check;
alter table invitation add constraint invitation_role_check
    check (role in ('owner', 'finance', 'it_admin', 'agent_owner', 'auditor', 'employee'));
