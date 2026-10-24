import type {
  CreateJobResponse,
  ExportResponse,
  I2VGenerateRequest,
  JobResponse,
  ListAssetsResponse,
  SpriteMaskPreviewRequest,
  SpriteMaskPreviewResponse,
  SpriteProcessRequest,
  T2IGenerateRequest,
} from "../types/api";

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8000/api/v1";

type RequestOptions = {
  method?: "GET" | "POST";
  body?: unknown;
};

async function requestJson<T>(
  endpoint: string,
  { method = "GET", body }: RequestOptions = {},
): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    method,
    headers: {
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}): ${endpoint}`);
  }
  return (await response.json()) as T;
}

export async function getHealth() {
  const healthBase = API_BASE_URL.replace("/api/v1", "");
  const response = await fetch(`${healthBase}/health`);
  if (!response.ok) {
    throw new Error(`Health check failed (${response.status})`);
  }
  return (await response.json()) as { status: string; service: string };
}

export async function createT2IJob(payload: T2IGenerateRequest) {
  return requestJson<CreateJobResponse>("/t2i/generate", {
    method: "POST",
    body: payload,
  });
}

export async function createI2VJob(payload: I2VGenerateRequest) {
  return requestJson<CreateJobResponse>("/i2v/generate", {
    method: "POST",
    body: payload,
  });
}

export async function getJob(jobId: string) {
  return requestJson<JobResponse>(`/jobs/${jobId}`);
}

export async function listAssets(projectId: string) {
  return requestJson<ListAssetsResponse>(
    `/assets?project_id=${encodeURIComponent(projectId)}`,
  );
}

export async function createSpritesProcessJob(payload: SpriteProcessRequest) {
  return requestJson<CreateJobResponse>("/sprites/process", {
    method: "POST",
    body: payload,
  });
}

export async function getExport(jobId: string) {
  return requestJson<ExportResponse>(`/exports/${jobId}`);
}

export async function previewSpriteMask(payload: SpriteMaskPreviewRequest) {
  return requestJson<SpriteMaskPreviewResponse>("/sprites/preview-mask", {
    method: "POST",
    body: payload,
  });
}
