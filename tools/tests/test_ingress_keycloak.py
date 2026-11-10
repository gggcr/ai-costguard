"""The chart routes the web client, the API, the gateway and Keycloak through an ingress, and can run Keycloak itself
(ADR 0035). The realm of the cluster is the local one without what only the local stack needs.

The rendering tests need the helm binary on PATH or in $HELM; without it they are skipped. The realm tests always run.
"""

import json
from pathlib import Path

import pytest

from test_helm import HELM, env_of, find, render

ROOT = Path(__file__).resolve().parents[2]
LOCAL = json.loads((ROOT / "infra/keycloak/costguard-realm.json").read_text("utf-8"))
CLUSTER = json.loads((ROOT / "deploy/helm/costguard/files/keycloak-realm.json").read_text("utf-8"))

helm = pytest.mark.skipif(HELM is None, reason="helm is not installed here")


def test_the_cluster_realm_has_the_roles_and_mappers_of_the_local_one():
    assert CLUSTER["realm"] == LOCAL["realm"]
    assert CLUSTER["roles"] == LOCAL["roles"]
    local = {c["clientId"]: c for c in LOCAL["clients"]}
    cluster = {c["clientId"]: c for c in CLUSTER["clients"]}
    assert set(cluster) == set(local) - {"costguard-e2e"}  # password grants are for local tests only
    for client in cluster:
        assert cluster[client].get("protocolMappers") == local[client].get("protocolMappers"), client


def test_the_cluster_realm_has_no_local_addresses_or_secrets():
    text = json.dumps(CLUSTER)
    assert "localhost" not in text and "-local" not in text
    clients = {c["clientId"]: c for c in CLUSTER["clients"]}
    assert clients["costguard-frontend"]["redirectUris"] == ["${COSTGUARD_WEB_URL}/*"]
    assert clients["costguard-privacy"]["secret"] == "${KEYCLOAK_PRIVACY_CLIENT_SECRET}"
    assert CLUSTER["sslRequired"] == "external" and CLUSTER["registrationAllowed"] is False


@helm
def test_off_by_default_and_the_frontend_runs_without_the_secret():
    docs = render()
    assert find(docs, "Ingress") == [] and find(docs, "Deployment", "costguard-keycloak") == []
    [frontend] = find(docs, "Deployment", "costguard-frontend")
    container = frontend["spec"]["template"]["spec"]["containers"][0]
    assert "envFrom" not in container and set(env_of(frontend)) == {"CORE_URL", "NGINX_RESOLVER"}
    assert env_of(frontend)["CORE_URL"]["value"] == "http://costguard-core.default.svc.cluster.local"
    assert {m["mountPath"] for m in container["volumeMounts"]} == {"/tmp", "/etc/nginx/conf.d"}
    assert container["readinessProbe"]["httpGet"] == {"path": "/", "port": "http"}
    assert find(docs, "Service", "costguard-frontend")[0]["spec"]["ports"][0]["targetPort"] == "http"


@helm
def test_every_host_gets_its_ingress_and_certificate():
    docs = render(sets=("ingress.enabled=true", "keycloak.enabled=true"))
    by_name = {i["metadata"]["name"]: i for i in find(docs, "Ingress")}
    assert set(by_name) == {"costguard-web", "costguard-api", "costguard-gateway", "costguard-keycloak"}
    backends = {n: i["spec"]["rules"][0]["http"]["paths"][0]["backend"]["service"]["name"] for n, i in by_name.items()}
    assert backends == {"costguard-web": "costguard-frontend", "costguard-api": "costguard-core",
                        "costguard-gateway": "costguard-gateway", "costguard-keycloak": "costguard-keycloak"}
    gateway = by_name["costguard-gateway"]["metadata"]["annotations"]
    assert gateway["nginx.ingress.kubernetes.io/proxy-buffering"] == "off"
    assert gateway["nginx.ingress.kubernetes.io/proxy-read-timeout"] == "600"
    assert by_name["costguard-web"]["metadata"]["annotations"]["nginx.ingress.kubernetes.io/proxy-body-size"] == "50m"
    for name, ingress in by_name.items():
        assert ingress["metadata"]["annotations"]["cert-manager.io/cluster-issuer"] == "letsencrypt"
        assert ingress["spec"]["tls"][0]["secretName"] == f"{name}-tls"
    # without cert-manager every host takes the one secret
    shared = render(sets=("ingress.enabled=true", "ingress.clusterIssuer="))
    assert {i["spec"]["tls"][0]["secretName"] for i in find(shared, "Ingress")} == {"costguard-tls"}
    assert all("cert-manager.io/cluster-issuer" not in i["metadata"]["annotations"] for i in find(shared, "Ingress"))


@helm
def test_keycloak_of_the_chart_is_the_issuer_of_every_service():
    docs = render(sets=("ingress.enabled=true", "keycloak.enabled=true", "ingress.hosts.keycloak=id.romashka.ru"))
    [core] = find(docs, "Deployment", "costguard-core")
    assert env_of(core)["KEYCLOAK_ISSUER_URI"]["value"] == "https://id.romashka.ru/realms/costguard"
    assert env_of(core)["KEYCLOAK_ADMIN_URL"]["value"] == "https://id.romashka.ru"
    [kc] = find(docs, "Deployment", "costguard-keycloak")
    container = kc["spec"]["template"]["spec"]["containers"][0]
    assert container["args"] == ["start", "--import-realm"]
    env = env_of(kc)
    assert env["KC_HOSTNAME"]["value"] == "https://id.romashka.ru" and env["KC_PROXY_HEADERS"]["value"] == "xforwarded"
    assert env["COSTGUARD_WEB_URL"]["value"] == "https://costguard.example.ru"
    assert container["envFrom"] == [{"secretRef": {"name": "costguard-keycloak"}}]
    assert container["readinessProbe"]["httpGet"]["port"] == "management"
    [realm] = find(docs, "ConfigMap", "costguard-keycloak-realm")
    assert json.loads(realm["data"]["costguard-realm.json"]) == CLUSTER
    assert find(docs, "Service", "costguard-keycloak-headless")[0]["spec"]["clusterIP"] == "None"
    assert find(docs, "PodDisruptionBudget", "costguard-keycloak")
    # Keycloak elsewhere: the services keep the external addresses
    [core] = find(render(), "Deployment", "costguard-core")
    assert env_of(core)["KEYCLOAK_ISSUER_URI"]["value"] == "https://id.costguard.example/realms/costguard"
