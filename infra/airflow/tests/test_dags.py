"""Loads the DAG files against a stand-in for Airflow and checks schedules and commands.

Airflow itself does not install on the Python of this repository (3.14), so the real parse happens in the
Airflow image (infra/local/docker-compose.yml, `make airflow`). This test catches what can break without it:
names, schedules, one task per tenant, and the commands the tasks run.
"""

import importlib.util
import subprocess
import sys
import types
from pathlib import Path

import pytest

# Imported before the fixture puts dags/ on the path, where costguard_common is the DAG helper module.
from costguard_connectors import agent as connector_agent

DAGS = Path(__file__).resolve().parents[1] / "dags"


class FakeDag:
    current = None

    def __init__(self, dag_id, **kw):
        self.dag_id, self.kw, self.tasks = dag_id, kw, []

    def __enter__(self):
        FakeDag.current = self
        return self

    def __exit__(self, *a):
        FakeDag.current = None


class FakeBash:
    def __init__(self, task_id, bash_command, **kw):
        self.task_id, self.bash_command, self.kw = task_id, bash_command, kw
        FakeDag.current.tasks.append(self)


@pytest.fixture
def load(monkeypatch, tmp_path):
    airflow = types.ModuleType("airflow")
    airflow.DAG = FakeDag
    operators = types.ModuleType("airflow.operators")
    bash = types.ModuleType("airflow.operators.bash")
    bash.BashOperator = FakeBash
    pendulum = types.ModuleType("pendulum")
    pendulum.timezone = lambda name: __import__("datetime").timezone(__import__("datetime").timedelta(hours=3), name)
    for name, module in {"airflow": airflow, "airflow.operators": operators, "airflow.operators.bash": bash,
                         "pendulum": pendulum}.items():
        monkeypatch.setitem(sys.modules, name, module)
    (tmp_path / "romashka.toml").write_text('tenant_id = "11111111-1111-1111-1111-111111111111"\n'
                                            '[onec]\nurl = "http://1c"\nuser = "u"\n[report]\nmonths = 6\n',
                                            "utf-8")
    (tmp_path / "Лютик-2.toml").write_text('tenant_id = "22222222-2222-2222-2222-222222222222"\n', "utf-8")
    (tmp_path / "broken.toml").write_text("tenant_id = ", "utf-8")
    monkeypatch.setenv("COSTGUARD_TENANTS_DIR", str(tmp_path))
    monkeypatch.syspath_prepend(str(DAGS))
    monkeypatch.delitem(sys.modules, "costguard_common", raising=False)

    def _load(file):
        spec = importlib.util.spec_from_file_location(file, DAGS / f"{file}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.dag

    return _load


def test_connectors_run_hourly_one_task_per_tenant(load):
    dag = load("connectors_hourly")
    assert dag.dag_id == "costguard_connectors"
    assert dag.kw["schedule"] == "@hourly" and dag.kw["max_active_runs"] == 1 and dag.kw["catchup"] is False
    assert sorted(t.task_id for t in dag.tasks) == ["pull_romashka", "pull_lyutik_2"] or \
        sorted(t.task_id for t in dag.tasks) == sorted(["pull_romashka", "pull_lyutik_2"])
    task = next(t for t in dag.tasks if t.task_id == "pull_romashka")
    assert task.bash_command.endswith("romashka.toml --once")
    assert task.kw["env"] == {"COSTGUARD_ENV_PREFIX": "ROMASHKA_"} and task.kw["append_env"] is True


def test_monthly_report_only_for_tenants_that_asked(load):
    dag = load("monthly_report")
    assert dag.kw["schedule"] == "0 6 1 * *"
    assert str(dag.kw["start_date"].tzinfo) == "Europe/Moscow"
    assert [t.task_id for t in dag.tasks] == ["report_romashka_xlsx", "report_romashka_pdf"]
    pdf = dag.tasks[1].bash_command
    assert '--format pdf --months 6' in pdf
    assert 'CORE_TOKEN="$ROMASHKA_CORE_TOKEN"' in pdf
    assert "{{ data_interval_start.strftime('%Y-%m') }}.pdf" in pdf


@pytest.mark.skipif(sys.platform == "win32", reason="bash on Windows is WSL, which may have no distribution")
def test_report_command_is_valid_shell(load):
    dag = load("monthly_report")
    command = dag.tasks[0].bash_command.replace("{{ data_interval_start.strftime('%Y-%m') }}", "2026-10")
    result = subprocess.run(["bash", "-n", "-c", command], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_a_tenant_with_only_the_new_connectors_gets_its_pull(load, tmp_path):
    import tomllib

    config = "\n".join([
        'tenant_id = "33333333-3333-3333-3333-333333333333"',
        "[google_workspace]", 'admin = "a@x"',
        "[okta]", 'url = "https://x.okta.example"',
        "[entra]", 'directory_id = "d"', 'client_id = "c"',
        "[merge]", "[tbank]",
        "[payments]", 'intermediaries = ["7700000009"]', ""])
    (tmp_path / "landysh.toml").write_text(config, "utf-8")
    dag = load("connectors_hourly")
    task = next(t for t in dag.tasks if t.task_id == "pull_landysh")
    assert task.bash_command.endswith("landysh.toml --once")
    sections = {k for k, v in tomllib.loads(config).items() if isinstance(v, dict)}
    assert sections <= set(connector_agent.SECTIONS)
