import uuid

import httpx

from costguard_common import normalized
from costguard_connectors import agent, scim

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")


def users(start: int) -> dict:
    everyone = [
        {"id": "1", "userName": "ivanov@corp.ru", "name": {"formatted": "Иванов Иван"}, "active": True,
         "emails": [{"value": "ivanov@corp.ru", "primary": True}]},
        {"id": "2", "userName": "petrova", "name": {"familyName": "Петрова", "givenName": "Анна"}, "active": False,
         "emails": [{"value": "old@corp.ru"}, {"value": "petrova@corp.ru", "primary": True}]},
        {"id": "3", "userName": "ci-bot", "active": True},
    ]
    page = everyone[start - 1:start - 1 + 2]
    return {"schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"], "totalResults": len(everyone),
            "startIndex": start, "itemsPerPage": len(page), "Resources": page}


def service() -> tuple[scim.Scim, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=users(int(request.url.params["startIndex"])))

    client = httpx.Client(base_url="https://api.notion.com/scim/v2", transport=httpx.MockTransport(handle))
    return scim.Scim(client, "Notion", "Notion Plus"), seen


def of(batch, kind):
    return [e for e in batch.entities if e.type == kind]


def test_active_accounts_of_a_service_are_its_licenses():
    connector, seen = service()
    batch = connector.pull(TENANT, None)
    assert [r.url.params["startIndex"] for r in seen] == ["1", "3"]  # pages until totalResults
    people = {e.payload["email"]: e.payload for e in of(batch, normalized.EntityType.EMPLOYEE)}
    assert set(people) == {"ivanov@corp.ru", "petrova@corp.ru"}  # the bot has no e-mail: no person
    # switched off there is not dismissed: SCIM says nothing about employment
    assert people["petrova@corp.ru"]["active"] is False and people["petrova@corp.ru"]["dismissed"] is False
    assert people["petrova@corp.ru"]["name"] == "Петрова Анна"
    [license_] = of(batch, normalized.EntityType.LICENSE_ASSIGNMENT)
    assert (license_.payload["vendor"], license_.payload["product"], license_.payload["employee"]) == (
        "Notion", "Notion Plus", "ivanov@corp.ru")
    assert license_.payload["last_active"] is None
    [subscription] = of(batch, normalized.EntityType.SUBSCRIPTION)
    assert subscription.payload["product"] == "Notion Plus"
    assert connector.name == "scim:notion:notion plus"


def test_the_agent_builds_one_connector_per_service():
    config = {"scim": [
        {"vendor": "Notion", "product": "Notion Plus", "url": "https://api.notion.com/scim/v2",
         "token_env": "SCIM_NOTION_TOKEN"},
        {"vendor": "Miro", "product": "Miro Business", "url": "https://miro.com/api/v1/scim",
         "token_env": "SCIM_MIRO_TOKEN"}]}
    built = agent.build(config, {"SCIM_NOTION_TOKEN": "n", "SCIM_MIRO_TOKEN": "m"})
    assert [c.name for c in built] == ["scim:notion:notion plus", "scim:miro:miro business"]
    found, broken = agent.build_each({"scim": [config["scim"][0]]}, {})
    assert found == [] and "scim" in broken  # the token is missing
