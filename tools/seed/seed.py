"""Demo data for one company, ООО «Ромашка», written as SQL for the local PostgreSQL.

    python tools/seed/seed.py > /tmp/seed.sql      # or: make demo

The tenant id matches the demo user of infra/keycloak/costguard-users-0.json (demo / demo). Ids are derived
from names, so running it again changes nothing (on conflict do nothing). Dates are relative to today, so the
audit engine finds something on its first run: an unused license, a license of a dismissed employee, free seats,
two subscriptions to one product, a contract that renews itself soon, a laptop not returned.

The SQL runs as the database owner of the compose stack, which bypasses RLS; that is acceptable for demo data and
never for the application, which runs as an ordinary role.
"""

from __future__ import annotations

import sys
import uuid
from datetime import date, timedelta

TENANT = uuid.UUID("0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a")


def uid(*parts: object) -> uuid.UUID:
    return uuid.uuid5(TENANT, ":".join(str(p) for p in parts))


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


def build(today: date) -> str:
    t = str(TENANT)
    departments = {name: uid("department", name) for name in ["ИТ", "Продажи", "Бухгалтерия"]}
    people = [
        ("Иванов Иван", "ivanov@romashka.example", "ИТ", "Руководитель ИТ", None),
        ("Петрова Анна", "petrova@romashka.example", "Продажи", "Менеджер", None),
        ("Сидоров Пётр", "sidorov@romashka.example", "Продажи", "Менеджер", today - timedelta(days=20)),
        ("Кузнецова Мария", "kuznetsova@romashka.example", "Бухгалтерия", "Главный бухгалтер", None),
        ("Смирнов Олег", "smirnov@romashka.example", "ИТ", "Разработчик", None),
    ]
    employees = {email: uid("employee", email) for _, email, *_ in people}
    vendors = {
        "Битрикс24": ("7709000001", "CRM"),
        "Яндекс": ("7700000002", "Почта и документы"),
        "Атлассиан": ("", "Разработка"),
    }
    vendor_ids = {name: uid("vendor", name) for name in vendors}
    subs = [
        ("bitrix", "Битрикс24", "Bitrix24", "Продажи", 5, 790, "month"),
        ("y360", "Яндекс", "Яндекс 360", "ИТ", 5, 3000, "year"),
        ("jira", "Атлассиан", "Jira", "ИТ", 3, 650, "month"),
        ("jira-sales", "Атлассиан", "JIRA", "Продажи", 2, 650, "month"),
    ]
    sub_ids = {key: uid("subscription", key) for key, *_ in subs}
    licenses = [
        ("bitrix", "petrova@romashka.example", today - timedelta(days=1)),
        ("bitrix", "sidorov@romashka.example", today - timedelta(days=25)),
        ("bitrix", "ivanov@romashka.example", today - timedelta(days=60)),
        ("y360", "ivanov@romashka.example", today),
        ("y360", "petrova@romashka.example", today),
        ("y360", "kuznetsova@romashka.example", today - timedelta(days=2)),
        ("y360", "smirnov@romashka.example", today),
        ("y360", "sidorov@romashka.example", today - timedelta(days=22)),
        ("jira", "smirnov@romashka.example", today),
        ("jira-sales", "petrova@romashka.example", today - timedelta(days=90)),
    ]
    sql = ["begin;\n", insert("tenant", [{"id": t, "name": "ООО «Ромашка»", "currency": "RUB", "country": "RU",
                                          "plan": "business", "status": "active"}])]
    # The business plan without an end (ADR 0033): the demo is never read-only and stays within every limit.
    sql.append(insert("tenant_plan", [{"tenant_id": t, "plan": "business", "trial_ends_at": None,
                                       "access_until": None, "version": 1, "updated_at": today.isoformat()}]))
    sql.append(insert("department", [{"id": str(i), "tenant_id": t, "name": n} for n, i in departments.items()]))
    sql.append(insert("employee", [
        {"id": str(employees[email]), "tenant_id": t, "department_id": str(departments[dept]), "name": name,
         "email": email, "position": pos, "dismissed_at": None if left is None else left.isoformat()}
        for name, email, dept, pos, left in people]))
    sql.append(insert("user_account", [{"id": str(uid("user", "demo")), "tenant_id": t, "name": "Демо Владелец",
                                        "email": "demo@romashka.example", "role": "owner", "employee_id": None}]))
    sql.append(insert("vendor", [{"id": str(vendor_ids[n]), "tenant_id": t, "name": n, "inn": inn, "category": cat}
                                 for n, (inn, cat) in vendors.items()]))
    sql.append(insert("contract", [{
        "id": str(uid("contract", "bitrix")), "tenant_id": t, "vendor_id": str(vendor_ids["Битрикс24"]),
        "number": "Б24-2025/17", "start_date": (today - timedelta(days=355)).isoformat(),
        "end_date": (today + timedelta(days=40)).isoformat(), "notice_days": 30, "auto_renew": True,
        "amount": 47400}]))
    sql.append(insert("subscription", [
        {"id": str(sub_ids[key]), "tenant_id": t, "vendor_id": str(vendor_ids[vendor]), "product": product,
         "contract_id": str(uid("contract", "bitrix")) if key == "bitrix" else None,
         "department_id": str(departments[dept]), "seats": seats, "price_per_seat": price, "billing_period": period,
         "renewal_date": (today + timedelta(days=40)).isoformat()}
        for key, vendor, product, dept, seats, price, period in subs]))
    sql.append(insert("license_assignment", [
        {"id": str(uid("license", key, email)), "tenant_id": t, "subscription_id": str(sub_ids[key]),
         "employee_id": str(employees[email]), "last_active": seen.isoformat()} for key, email, seen in licenses]))
    assets = [("INV-0001", "ThinkPad T14", "LAPTOP", 95000, "ASSIGNED"), ("INV-0002", "MacBook Air", "LAPTOP", 120000,
              "ASSIGNED"), ("INV-0003", "Dell P2422H", "MONITOR", 18000, "IN_STOCK")]
    sql.append(insert("asset", [{"id": str(uid("asset", n)), "tenant_id": t, "inventory_number": n, "model": m,
                                 "type": ty, "cost": c, "status": s} for n, m, ty, c, s in assets]))
    sql.append(insert("asset_assignment", [
        {"id": str(uid("issue", "INV-0001")), "tenant_id": t, "asset_id": str(uid("asset", "INV-0001")),
         "employee_id": str(employees["sidorov@romashka.example"]),
         "issued_at": (today - timedelta(days=400)).isoformat(), "return_due": None, "returned_at": None},
        {"id": str(uid("issue", "INV-0002")), "tenant_id": t, "asset_id": str(uid("asset", "INV-0002")),
         "employee_id": str(employees["kuznetsova@romashka.example"]),
         "issued_at": (today - timedelta(days=100)).isoformat(), "return_due": None, "returned_at": None},
        {"id": str(uid("issue", "INV-0003")), "tenant_id": t, "asset_id": str(uid("asset", "INV-0003")),
         "employee_id": str(employees["smirnov@romashka.example"]),
         "issued_at": (today - timedelta(days=300)).isoformat(), "return_due": None,
         "returned_at": (today - timedelta(days=120)).isoformat()},
    ]))
    sql.append("commit;\n")
    return "\n".join(s for s in sql if s)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    print(build(date.today()))
