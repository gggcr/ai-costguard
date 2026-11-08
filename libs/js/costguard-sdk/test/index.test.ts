import assert from "node:assert/strict";
import { test } from "node:test";

import { ApprovalRequired, CostGuard, Refused } from "../src/index.ts";

type Call = { method: string; path: string; body: unknown; headers: Record<string, string> };
type Answer = [number, unknown] | ((call: Call) => [number, unknown]);

function gateway(routes: Record<string, Answer>) {
  const calls: Call[] = [];
  const fake = (async (url: string, init: RequestInit) => {
    const path = new URL(url).pathname;
    const call = { method: init.method ?? "GET", path, body: init.body ? JSON.parse(String(init.body)) : undefined,
      headers: init.headers as Record<string, string> };
    calls.push(call);
    const route = routes[path];
    const [status, body] = typeof route === "function" ? route(call) : route;
    return new Response(body === null ? "oops" : JSON.stringify(body), { status });
  }) as typeof fetch;
  const slept: number[] = [];
  const cg = new CostGuard({ apiKey: "cg_live_x", baseUrl: "http://gw/", fetch: fake,
    sleep: async (ms) => { slept.push(ms); } });
  return { cg, calls, slept };
}

const OK: Answer = [200, { authorizationId: "op-1", status: "authorized", reserved: 1290 }];

test("spend settles what the work returns", async () => {
  const { cg, calls } = gateway({ "/v1/spend/authorize": OK, "/v1/spend/settle": [200, {}] });
  const total = await cg.spend({ vendor: "Ozon", amount: 1290, product: "Картридж", operationId: "op-1" },
    async () => 1100.5);
  assert.equal(total, 1100.5);
  assert.deepEqual(calls[0].body, { vendor: "Ozon", amount: 1290, product: "Картридж", taskId: null,
    kind: "paid_api" });
  assert.equal(calls[0].headers["Idempotency-Key"], "op-1");
  assert.equal(calls[0].headers.Authorization, "Bearer cg_live_x");
  assert.deepEqual(calls[1].body, { authorizationId: "op-1", amount: 1100.5 });
});

test("work that returns nothing settles the reserve", async () => {
  const { cg, calls } = gateway({ "/v1/spend/authorize": OK, "/v1/spend/settle": [200, {}] });
  await cg.spend({ vendor: "Ozon", amount: 1290 }, async () => {});
  assert.deepEqual(calls[1].body, { authorizationId: "op-1", amount: 1290 });
});

test("an error releases the reserve and is thrown again", async () => {
  const { cg, calls } = gateway({ "/v1/spend/authorize": OK, "/v1/spend/release": [200, {}] });
  await assert.rejects(cg.spend({ vendor: "Ozon", amount: 1 }, async () => { throw new Error("shop down"); }),
    /shop down/);
  assert.equal(calls[1].path, "/v1/spend/release");
});

test("a refusal", async () => {
  const { cg } = gateway({ "/v1/spend/authorize": [402, { error: { type: "budget_exceeded" } }] });
  await assert.rejects(cg.authorize({ vendor: "Ozon", amount: 1e6 }),
    (e: unknown) => e instanceof Refused && e.status === 402 && e.type === "budget_exceeded");
});

test("approval flow", async () => {
  const answers = ["pending", "pending", "approved"];
  const { cg, slept } = gateway({
    "/v1/spend/authorize": [403, { error: { type: "approval_required", message: "big", approvalId: "op-9" } }],
    "/v1/spend/approvals/op-9": () => [200, { approvalId: "op-9", status: answers.shift() }],
  });
  const error = await cg.authorize({ vendor: "Ozon", amount: 50000, operationId: "op-9" }).catch((e) => e);
  assert.ok(error instanceof ApprovalRequired);
  assert.equal(await cg.waitForApproval(error.approvalId, { everyMs: 5 }), "approved");
  assert.deepEqual(slept, [5, 5]);
});

test("a body that is not JSON", async () => {
  const { cg } = gateway({ "/v1/spend/settle": [502, null] });
  await assert.rejects(cg.settle("op", 1), (e: unknown) => e instanceof Refused && e.type === "http_502");
});

test("provider url", () => {
  const { cg } = gateway({});
  assert.equal(cg.providerUrl("openai"), "http://gw/v1/providers/openai");
  assert.throws(() => cg.providerUrl("mistral" as never));
});
