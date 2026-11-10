"""Each forecast run as a run in MLflow (ADR 0044): which model won the backtest for the company and each department,
its error on the history and the one that came next. Over weeks this shows whether the forecasts get better and
when a model stops winning.

The REST API of MLflow (2.0) through httpx: the mlflow package is heavy and the forecast needs none of it. A tracking
server that does not answer is logged and skipped: the forecast is published all the same. Names of departments do
not go to MLflow, only their ids.
"""

from __future__ import annotations

import logging
import time

import httpx

log = logging.getLogger(__name__)
EXPERIMENT = "costguard-forecast"
API = "/api/2.0/mlflow"


def _metric(key: str, value: float | None, at: int) -> list[dict]:
    return [] if value is None else [{"key": key, "value": float(value), "timestamp": at, "step": 0}]


def entries(document: dict) -> tuple[list[dict], list[dict]]:
    """Params and metrics of a forecast document (publish.document)."""
    at = int(time.time() * 1000)
    company = document["company"]
    params = [{"key": "horizon", "value": str(document["horizon"])},
              {"key": "generated_by", "value": str(document.get("generatedBy", ""))},
              {"key": "company.model", "value": str(company["model"])},
              {"key": "company.challenger", "value": str(company.get("challenger") or "")}]
    metrics = (_metric("company.wape", company.get("wape"), at) + _metric("company.mase", company.get("mase"), at)
               + _metric("company.challenger_wape", company.get("challengerWape"), at)
               + _metric("departments", len(document["departments"]), at)
               + _metric("history_months", len(company.get("history", [])), at))
    wapes = []
    for d in document["departments"]:
        key = d.get("departmentId") or "none"
        params.append({"key": f"department.{key}.model", "value": str(d["model"])})
        metrics += _metric(f"department.{key}.wape", d.get("wape"), at)
        if d.get("wape") is not None:
            wapes.append(float(d["wape"]))
    if wapes:
        metrics += _metric("departments.mean_wape", sum(wapes) / len(wapes), at)
    agent_list = document.get("agents") or []
    metrics += _metric("agents", len(agent_list), at)
    won: dict[str, int] = {}
    for a in agent_list:
        won[a["model"]] = won.get(a["model"], 0) + 1
    for model, n in sorted(won.items()):
        metrics += _metric(f"agents.won.{model}", n, at)
    agent_wapes = [float(a["wape"]) for a in agent_list if a.get("wape") is not None]
    if agent_wapes:
        metrics += _metric("agents.mean_wape", sum(agent_wapes) / len(agent_wapes), at)
    # log-batch takes at most 100 params and 1000 metrics
    return params[:100], metrics[:1000]


class Tracking:
    def __init__(self, client: httpx.Client, experiment: str = EXPERIMENT) -> None:
        self.client = client  # base_url: the tracking server, such as http://mlflow:5000
        self.experiment = experiment

    def _post(self, path: str, body: dict) -> dict:
        r = self.client.post(API + path, json=body)
        r.raise_for_status()
        return r.json()

    def _experiment_id(self) -> str:
        r = self.client.get(API + "/experiments/get-by-name", params={"experiment_name": self.experiment})
        if r.status_code == 404 or (r.status_code == 400 and "RESOURCE_DOES_NOT_EXIST" in r.text):
            return self._post("/experiments/create", {"name": self.experiment})["experiment_id"]
        r.raise_for_status()
        return r.json()["experiment"]["experiment_id"]

    def log(self, tenant: str, document: dict) -> str | None:
        """The run id, or None when the server could not take it."""
        try:
            started = int(time.time() * 1000)
            run = self._post("/runs/create", {
                "experiment_id": self._experiment_id(), "start_time": started,
                "run_name": f"forecast-{time.strftime('%Y-%m-%d')}",
                "tags": [{"key": "costguard.tenant", "value": tenant},
                         {"key": "costguard.currency", "value": str(document.get("currency", ""))}]})["run"]
            run_id = run["info"]["run_id"]
            params, metrics = entries(document)
            self._post("/runs/log-batch", {"run_id": run_id, "params": params, "metrics": metrics})
            self._post("/runs/update", {"run_id": run_id, "status": "FINISHED", "end_time": int(time.time() * 1000)})
            return run_id
        except (httpx.HTTPError, KeyError, ValueError) as e:
            log.warning("The forecast run was not logged to MLflow: %s", e)
            return None
