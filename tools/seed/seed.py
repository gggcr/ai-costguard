"""Synthetic demo data for two companies, written as SQL for the local PostgreSQL.

    python tools/seed/seed.py > /tmp/seed.sql      # or: make demo

- ООО «Ромашка» (RUB, Russia, Russian): demo / demo is the owner, ivanov / demo an employee.
- Brightline Analytics Inc. (USD, United States, English): demo-us / demo is the owner, ecarter / demo an employee.

Every name, e-mail and amount is made up. The tenant ids match the users of infra/keycloak/costguard-users-0.json.
Ids are derived from names, so running it again changes nothing (on conflict do nothing). Dates are relative to
today, so the audit engine finds something on its first run: an unused license, a license of a dismissed employee,
free seats, two subscriptions to one product, a contract that renews itself soon, a laptop not returned.

The SQL runs as the database owner of the compose stack, which bypasses RLS; that is acceptable for demo data and
never for the application, which runs as an ordinary role.
"""

from __future__ import annotations

import sys
import uuid
from dataclasses import dataclass
from datetime import date, timedelta

TENANT = uuid.UUID("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a")
TENANT_US = uuid.UUID("6b1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e")


@dataclass(frozen=True)
class Company:
    tenant: uuid.UUID
    tenant_row: dict
    owner: tuple[str, str]  # (name, email) of the owner's account
    employee_login: str  # e-mail of the employee who signs in to the cabinet
    departments: list[str]
    people: list[tuple]  # (name, email, department, position, days since dismissal or None)
    vendors: dict[str, tuple[str, str]]  # name: (tax id, category)
    subs: list[tuple]  # (key, vendor, product, department, seats, price per seat, period)
    licenses: list[tuple[str, str, int]]  # (subscription key, e-mail, days since last activity)
    contract: tuple[str, str, int]  # (vendor, number, amount) of the contract of the first subscription
    assets: list[tuple]  # (inventory number, model, type, cost, status)
    issues: list[tuple]  # (inventory number, e-mail, days since issued, days since returned or None)


def romashka() -> Company:
    mail = "@romashka.example"
    return Company(
        tenant=TENANT,
        tenant_row={"name": "ООО «Ромашка»", "currency": "RUB", "country": "RU"},
        owner=("Демо Владелец", "demo" + mail),
        employee_login="ivanov" + mail,
        departments=["ИТ", "Продажи", "Бухгалтерия"],
        people=[
            ("Иванов Иван", "ivanov" + mail, "ИТ", "Руководитель ИТ", None),
            ("Петрова Анна", "petrova" + mail, "Продажи", "Менеджер", None),
            ("Сидоров Пётр", "sidorov" + mail, "Продажи", "Менеджер", 20),
            ("Кузнецова Мария", "kuznetsova" + mail, "Бухгалтерия", "Главный бухгалтер", None),
            ("Смирнов Олег", "smirnov" + mail, "ИТ", "Разработчик", None),
        ],
        vendors={"Битрикс24": ("7709000001", "CRM"), "Яндекс": ("7700000002", "Почта и документы"),
                 "Атлассиан": ("", "Разработка")},
        subs=[
            ("bitrix", "Битрикс24", "Bitrix24", "Продажи", 5, 790, "month"),
            ("y360", "Яндекс", "Яндекс 360", "ИТ", 5, 3000, "year"),
            ("jira", "Атлассиан", "Jira", "ИТ", 3, 650, "month"),
            ("jira-sales", "Атлассиан", "JIRA", "Продажи", 2, 650, "month"),
        ],
        licenses=[
            ("bitrix", "petrova" + mail, 1), ("bitrix", "sidorov" + mail, 25), ("bitrix", "ivanov" + mail, 60),
            ("y360", "ivanov" + mail, 0), ("y360", "petrova" + mail, 0), ("y360", "kuznetsova" + mail, 2),
            ("y360", "smirnov" + mail, 0), ("y360", "sidorov" + mail, 22), ("jira", "smirnov" + mail, 0),
            ("jira-sales", "petrova" + mail, 90),
        ],
        contract=("Битрикс24", "Б24-2025/17", 47400),
        assets=[("INV-0001", "ThinkPad T14", "LAPTOP", 95000, "ASSIGNED"),
                ("INV-0002", "MacBook Air", "LAPTOP", 120000, "ASSIGNED"),
                ("INV-0003", "Dell P2422H", "MONITOR", 18000, "IN_STOCK")],
        issues=[("INV-0001", "sidorov" + mail, 400, None), ("INV-0002", "kuznetsova" + mail, 100, None),
                ("INV-0003", "smirnov" + mail, 300, 120)],
    )


def brightline() -> Company:
    """An American company for Western buyers: the same situations, in dollars and in English (ADR 0072–0074)."""
    mail = "@brightline.example"
    return Company(
        tenant=TENANT_US,
        tenant_row={"name": "Brightline Analytics Inc.", "currency": "USD", "country": "US",
                    "time_zone": "America/New_York", "language": "en"},
        owner=("Alex Morgan", "alex.morgan" + mail),
        employee_login="emily.carter" + mail,
        departments=["Engineering", "Sales", "Finance"],
        people=[
            ("Emily Carter", "emily.carter" + mail, "Engineering", "Head of IT", None),
            ("James Miller", "james.miller" + mail, "Sales", "Account Executive", None),
            ("Robert Brown", "robert.brown" + mail, "Sales", "Account Executive", 20),
            ("Linda Garcia", "linda.garcia" + mail, "Finance", "Controller", None),
            ("Michael Davis", "michael.davis" + mail, "Engineering", "Software Engineer", None),
        ],
        vendors={"Salesforce": ("", "CRM"), "Google": ("", "Email and documents"),
                 "Atlassian": ("", "Development")},
        subs=[
            ("crm", "Salesforce", "Sales Cloud", "Sales", 5, 100, "month"),
            ("workspace", "Google", "Google Workspace", "Engineering", 5, 168, "year"),
            ("jira", "Atlassian", "Jira", "Engineering", 3, 8.6, "month"),
            ("jira-sales", "Atlassian", "Jira", "Sales", 2, 8.6, "month"),
        ],
        licenses=[
            ("crm", "james.miller" + mail, 1), ("crm", "robert.brown" + mail, 25), ("crm", "emily.carter" + mail, 60),
            ("workspace", "emily.carter" + mail, 0), ("workspace", "james.miller" + mail, 0),
            ("workspace", "linda.garcia" + mail, 2), ("workspace", "michael.davis" + mail, 0),
            ("workspace", "robert.brown" + mail, 22), ("jira", "michael.davis" + mail, 0),
            ("jira-sales", "james.miller" + mail, 90),
        ],
        contract=("Salesforce", "SF-2025-0417", 6000),
        assets=[("INV-0001", "ThinkPad T14", "LAPTOP", 1400, "ASSIGNED"),
                ("INV-0002", "MacBook Air", "LAPTOP", 1199, "ASSIGNED"),
                ("INV-0003", "Dell P2422H", "MONITOR", 230, "IN_STOCK")],
        issues=[("INV-0001", "robert.brown" + mail, 400, None), ("INV-0002", "linda.garcia" + mail, 100, None),
                ("INV-0003", "michael.davis" + mail, 300, 120)],
    )


COMPANIES = [romashka(), brightline()]


def q(value: object) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int | float):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def insert(table: str, rows: list[dict]) -> str:
    if not rows:
        return ""
    columns = list(rows[0])
    values = ",\n  ".join("(" + ", ".join(q(r[c]) for c in columns) + ")" for r in rows)
    return f"insert into {table} ({', '.join(columns)}) values\n  {values}\non conflict do nothing;\n"


def company(c: Company, today: date) -> list[str]:
    t = str(c.tenant)

    def uid(*parts: object) -> uuid.UUID:
        return uuid.uuid5(c.tenant, ":".join(str(p) for p in parts))

    def ago(days: int | None) -> str | None:
        return None if days is None else (today - timedelta(days=days)).isoformat()

    departments = {name: uid("department", name) for name in c.departments}
    employees = {email: uid("employee", email) for _, email, *_ in c.people}
    vendor_ids = {name: uid("vendor", name) for name in c.vendors}
    sub_ids = {key: uid("subscription", key) for key, *_ in c.subs}
    first = c.subs[0][0]
    employee = next(p for p in c.people if p[1] == c.employee_login)
    sql = [insert("tenant", [{"id": t, **c.tenant_row, "plan": "business", "status": "active"}])]
    # The business plan without an end (ADR 0037): the demo is never read-only and stays within every limit.
    sql.append(insert("tenant_plan", [{"tenant_id": t, "plan": "business", "trial_ends_at": None,
                                       "access_until": None, "version": 1, "updated_at": today.isoformat()}]))
    sql.append(insert("department", [{"id": str(i), "tenant_id": t, "name": n} for n, i in departments.items()]))
    sql.append(insert("employee", [
        {"id": str(employees[email]), "tenant_id": t, "department_id": str(departments[dept]), "name": name,
         "email": email, "position": pos, "dismissed_at": ago(left)}
        for name, email, dept, pos, left in c.people]))
    # The owner signs in to the console; the employee signs in to the cabinet only (ADR 0051)
    sql.append(insert("user_account", [
        {"id": str(uid("user", "demo")), "tenant_id": t, "name": c.owner[0], "email": c.owner[1],
         "role": "owner", "employee_id": None},
        {"id": str(uid("user", c.employee_login.split("@")[0].split(".")[0])), "tenant_id": t, "name": employee[0],
         "email": c.employee_login, "role": "employee", "employee_id": str(employees[c.employee_login])}]))
    # Keycloak of the local stack keeps no volume: every new container gives the demo users new subjects, while
    # PostgreSQL keeps its rows. A demo account still linked to the old subject could sign in to nothing it may
    # change (an approval answers 403 decider_account_required). Unlinked, it is linked again at the next sign-in by
    # its verified e-mail (FirstLogin, ADR 0069), as on the first run. Only these synthetic accounts are touched.
    demo_accounts = ", ".join(q(str(uid("user", name))) for name in
                              ("demo", c.employee_login.split("@")[0].split(".")[0]))
    sql.append(f"update user_account set keycloak_subject = null where tenant_id = {q(t)} "
               f"and id in ({demo_accounts});\n")
    sql.append(insert("vendor", [{"id": str(vendor_ids[n]), "tenant_id": t, "name": n, "inn": inn, "category": cat}
                                 for n, (inn, cat) in c.vendors.items()]))
    vendor, number, amount = c.contract
    sql.append(insert("contract", [{
        "id": str(uid("contract", first)), "tenant_id": t, "vendor_id": str(vendor_ids[vendor]),
        "number": number, "start_date": ago(355), "end_date": (today + timedelta(days=40)).isoformat(),
        "notice_days": 30, "auto_renew": True, "amount": amount}]))
    sql.append(insert("subscription", [
        {"id": str(sub_ids[key]), "tenant_id": t, "vendor_id": str(vendor_ids[vendor]), "product": product,
         "contract_id": str(uid("contract", first)) if key == first else None,
         "department_id": str(departments[dept]), "seats": seats, "price_per_seat": price, "billing_period": period,
         "renewal_date": (today + timedelta(days=40)).isoformat()}
        for key, vendor, product, dept, seats, price, period in c.subs]))
    sql.append(insert("license_assignment", [
        {"id": str(uid("license", key, email)), "tenant_id": t, "subscription_id": str(sub_ids[key]),
         "employee_id": str(employees[email]), "last_active": ago(seen)} for key, email, seen in c.licenses]))
    sql.append(insert("asset", [{"id": str(uid("asset", n)), "tenant_id": t, "inventory_number": n, "model": m,
                                 "type": ty, "cost": cost, "status": s} for n, m, ty, cost, s in c.assets]))
    sql.append(insert("asset_assignment", [
        {"id": str(uid("issue", n)), "tenant_id": t, "asset_id": str(uid("asset", n)),
         "employee_id": str(employees[email]), "issued_at": ago(issued), "return_due": None,
         "returned_at": ago(returned)} for n, email, issued, returned in c.issues]))
    return sql


def build(today: date) -> str:
    sql = ["begin;\n"]
    for c in COMPANIES:
        sql.extend(company(c, today))
    sql.append("commit;\n")
    return "\n".join(s for s in sql if s)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    print(build(date.today()))
