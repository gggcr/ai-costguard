/**
 * CostGuard SDK for agents.
 *
 * ```ts
 * const cg = new CostGuard({ apiKey: "cg_live_...", baseUrl: "https://gateway.example.ru" });
 * const total = await cg.spend({ vendor: "Ozon", amount: 1290, product: "Картридж",
 *   taskGoal: "Картриджи для принтеров бухгалтерии" }, async (op) => {
 *   const order = await buy();       // runs only if the gateway allowed the amount
 *   return order.total;              // settled as the real amount; nothing returned settles the reserve
 * });
 * ```
 *
 * An error thrown by the callback releases the reserve and is thrown again. A refusal throws `Refused`; an
 * operation held for a person throws `ApprovalRequired`, and `waitForApproval(e.approvalId)` waits for the
 * decision; after "approved", spending again with the same `operationId` goes through.
 *
 * `taskGoal` is the goal of the agent's task. Above the approval threshold of the mandate the gateway's AI assessor
 * checks the operation against it and lets it pass without a person when it clearly fits; without a goal a person
 * always decides. For a provider's own client, pass `CostGuard.headers({ taskId, taskGoal })` as default headers.
 */

export const PROVIDERS = ["openai", "anthropic", "gigachat", "yandex"] as const;
export type Provider = (typeof PROVIDERS)[number];
export type ApprovalState = "pending" | "approved" | "rejected" | "expired";
export const TASK_HEADER = "X-CostGuard-Task";
export const GOAL_HEADER = "X-CostGuard-Task-Goal";

export class CostGuardError extends Error {
  readonly status: number;
  readonly type: string;

  constructor(status: number, type: string, message = "") {
    super(`${status} ${type}: ${message || type}`);
    this.status = status;
    this.type = type;
  }
}

/** The gateway said no: budget_exceeded, policy_denied, agent_suspended, rate_limited, duplicate_operation… */
export class Refused extends CostGuardError {}

export class ApprovalRequired extends CostGuardError {
  readonly approvalId: string;

  constructor(status: number, message: string, approvalId: string) {
    super(status, "approval_required", message);
    this.approvalId = approvalId;
  }
}

export interface SpendRequest {
  vendor: string;
  amount: number;
  product?: string;
  taskId?: string;
  /** The goal of the task, for the AI assessor. Sent percent-encoded in X-CostGuard-Task-Goal. */
  taskGoal?: string;
  kind?: "paid_api" | "llm_call";
  /** The operation id; reuse it to retry an operation after an approval. A new UUID when absent. */
  operationId?: string;
}

export interface Authorization {
  authorizationId: string;
  reserved: number;
}

export interface Options {
  apiKey: string;
  baseUrl?: string;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
}

type ErrorBody = { error?: { type?: string; message?: string; approvalId?: string } };

export class CostGuard {
  readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly fetchFn: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(options: Options) {
    this.apiKey = options.apiKey;
    this.baseUrl = (options.baseUrl ?? "http://localhost:8081").replace(/\/+$/, "");
    this.fetchFn = options.fetch ?? fetch;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  private async request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}):
      Promise<T> {
    const response = await this.fetchFn(this.baseUrl + path, {
      method,
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let parsed: unknown = {};
    try {
      parsed = await response.json();
    } catch {
      parsed = {};
    }
    if (response.ok) {
      return parsed as T;
    }
    const error = (parsed as ErrorBody)?.error ?? {};
    const type = error.type ?? `http_${response.status}`;
    if (type === "approval_required") {
      throw new ApprovalRequired(response.status, error.message ?? "", error.approvalId ?? "");
    }
    throw new Refused(response.status, type, error.message ?? "");
  }

  /**
   * Headers that tell the gateway the task and its goal. A header carries only ASCII, so the goal is
   * percent-encoded UTF-8; the gateway decodes it.
   */
  static headers({ taskId, taskGoal }: { taskId?: string; taskGoal?: string } = {}): Record<string, string> {
    const out: Record<string, string> = {};
    if (taskId) {
      out[TASK_HEADER] = taskId;
    }
    const goal = taskGoal?.trim();
    if (goal) {
      out[GOAL_HEADER] = encodeURIComponent(goal);
    }
    return out;
  }

  /** Runs the check ladder and reserves the amount. */
  async authorize(request: SpendRequest): Promise<Authorization> {
    const operationId = request.operationId ?? crypto.randomUUID();
    const body = await this.request<{ authorizationId?: string; reserved?: number }>("POST", "/v1/spend/authorize", {
      vendor: request.vendor,
      amount: request.amount,
      product: request.product ?? null,
      taskId: request.taskId ?? null,
      kind: request.kind ?? "paid_api",
    }, { "Idempotency-Key": operationId, ...CostGuard.headers({ taskGoal: request.taskGoal }) });
    return { authorizationId: body.authorizationId ?? operationId, reserved: body.reserved ?? request.amount };
  }

  settle(authorizationId: string, amount: number): Promise<unknown> {
    return this.request("POST", "/v1/spend/settle", { authorizationId, amount });
  }

  release(authorizationId: string): Promise<unknown> {
    return this.request("POST", "/v1/spend/release", { authorizationId });
  }

  /** Authorizes, runs the work, settles what it returns (or the reserve), and releases the reserve on an error. */
  async spend<T extends number | void>(request: SpendRequest, work: (op: Authorization) => Promise<T>): Promise<T> {
    const op = await this.authorize(request);
    let result: T;
    try {
      result = await work(op);
    } catch (error) {
      await this.release(op.authorizationId);
      throw error;
    }
    await this.settle(op.authorizationId, typeof result === "number" ? result : op.reserved);
    return result;
  }

  async approval(approvalId: string): Promise<ApprovalState> {
    const body = await this.request<{ status: ApprovalState }>("GET",
      `/v1/spend/approvals/${encodeURIComponent(approvalId)}`);
    return body.status;
  }

  async waitForApproval(approvalId: string, { timeoutMs = 3_600_000, everyMs = 10_000 } = {}):
      Promise<ApprovalState> {
    let waited = 0;
    for (;;) {
      const status = await this.approval(approvalId);
      if (status !== "pending" || waited >= timeoutMs) {
        return status;
      }
      await this.sleep(everyMs);
      waited += everyMs;
    }
  }

  /** Base URL for the provider's own client (OpenAI SDK and the like), used with the agent key. */
  providerUrl(provider: Provider): string {
    if (!PROVIDERS.includes(provider)) {
      throw new Error(`provider must be one of ${PROVIDERS.join(", ")}`);
    }
    return `${this.baseUrl}/v1/providers/${provider}`;
  }
}
