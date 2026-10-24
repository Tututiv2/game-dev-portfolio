export type JobStatus =
  | "queued"
  | "running"
  | "succeeded"
  | "failed"
  | "cancelled";

export type JobKind = "t2i" | "i2v" | "sprites_process";

export type AssetType =
  | "image"
  | "video"
  | "frames_zip"
  | "spritesheet"
  | "metadata_json"
  | "code_snippet";

export type CreateJobResponse = {
  job_id: string;
  status: JobStatus;
};

export type JobResponse = {
  job_id: string;
  project_id: string;
  kind: JobKind;
  status: JobStatus;
  progress: number;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
  output_asset_ids: string[];
};

export type Asset = {
  id: string;
  project_id: string;
  type: AssetType;
  url: string;
  mime_type: string;
  width: number | null;
  height: number | null;
  duration_ms: number | null;
  frame_count: number | null;
  metadata: Record<string, unknown>;
  created_at: string;
};

export type ListAssetsResponse = {
  items: Asset[];
};

export type T2IGenerateRequest = {
  project_id: string;
  prompt: string;
  negative_prompt?: string | null;
  model: string;
  aspect_ratio: "1:1" | "16:9" | "9:16" | "4:3" | "3:4";
  seed?: number | null;
  reference_asset_id?: string | null;
};

export type I2VGenerateRequest = {
  project_id: string;
  prompt: string;
  model: string;
  reference_image_asset_id: string;
  duration_seconds: number;
  resolution: string;
  aspect_ratio: "1:1" | "16:9" | "9:16" | "4:3" | "3:4";
  fps_hint?: number;
};

export type AlignmentMode =
  | "union"
  | "bottom_center"
  | "both_centered"
  | "vertical_only"
  | "horizontal_only";

export type MaskingOptions = {
  key_color_hex?: string;
  tolerance?: number;
  /** Width of the soft alpha ramp. 0 restores a hard, un-antialiased edge. */
  softness?: number;
  /** Remove the key hue bounced onto the subject. Defaults to true. */
  despill?: boolean;
  /** corridor_key only: how much a pure brightness change counts as subject. */
  luma_weight?: number;
};

export type SpriteProcessRequest = {
  project_id: string;
  source_video_asset_id: string;
  masking_mode: "chroma" | "corridor_key";
  masking_options: MaskingOptions;
  frame_selection: {
    enabled_indices: number[];
  };
  /**
   * "union" is the default. The three legacy modes crop each frame to its own
   * bounding box and re-centre it, which deletes an animation's vertical
   * motion - see spritekit.align_frames.
   */
  alignment_mode: AlignmentMode;
  export_options: {
    sheet_layout: "horizontal";
    include_frames_zip: boolean;
    generate_code_snippet: boolean;
    /** Defaults to the source clip's own frame rate. */
    fps?: number;
  };
};

export type ExportResponse = {
  job_id: string;
  project_id: string;
  status: JobStatus;
  spritesheet_asset_id: string;
  frames_zip_asset_id: string | null;
  metadata_asset_id: string;
  code_snippet_asset_id: string | null;
  alignment: {
    offset_x: number;
    offset_y: number;
  };
  frame: {
    width: number;
    height: number;
    count: number;
  };
  sheet: {
    layout: string;
    width: number;
    height: number;
    source_video_url?: string;
  };
  /**
   * The subject's anchor within a frame - bottom-centre, its ground line.
   * Rotate and position about this, NOT the frame centre: frames are cropped
   * per-subject, so the frame centre drifts whenever the subject's size does.
   */
  pivot?: {
    x: number;
    y: number;
  };
  /** Frames the export could not use, and why. Empty on a clean run. */
  dropped_frames?: Array<{ index: number; reason: string }>;
  fps?: number;
};

export type SpriteMaskPreviewRequest = {
  project_id: string;
  source_video_asset_id: string;
  masking_mode: "chroma" | "corridor_key";
  masking_options: MaskingOptions;
  frame_index: number;
};

export type SpriteMaskPreviewResponse = {
  preview_id: string;
  url: string;
};
