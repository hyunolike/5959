variable "project_id" {
  type        = string
  description = "Explicit existing GCP project; never inferred from gcloud defaults."
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "Set the approved GCP project ID."
  }
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "zone" {
  type    = string
  default = "us-central1-a"
}

variable "machine_type" {
  type    = string
  default = "e2-small"
}

variable "boot_image" {
  type        = string
  description = "Pinned image, not an image family, to prevent unintended VM replacements."
  default     = "projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20260918"
}

variable "api_image" {
  type        = string
  description = "Immutable Artifact Registry digest. Empty provisions infrastructure only."
  default     = ""
  validation {
    condition     = var.api_image == "" || can(regex("@sha256:[a-f0-9]{64}$", var.api_image))
    error_message = "Use an immutable image digest, not latest."
  }
}

variable "web_image" {
  type        = string
  description = "Immutable web image digest. Empty means Cloud Run is not created yet."
  default     = ""
  validation {
    condition     = var.web_image == "" || can(regex("@sha256:[a-f0-9]{64}$", var.web_image))
    error_message = "Use an immutable image digest, not latest."
  }
}

variable "kakao_login_enabled" {
  type        = bool
  description = "Enable Kakao login; false permits a Google-only deployment."
  default     = true
}

variable "ai_companion_enabled" {
  type        = bool
  description = "Let the AI companions comment on posts nobody answered. Ships off; turn on after reading real model output."
  default     = false
}

variable "ai_companion_daily_limit" {
  type        = number
  description = "How many times per day (Asia/Seoul) the API may ask the model for a companion comment."
  default     = 30
  validation {
    condition     = var.ai_companion_daily_limit >= 0 && floor(var.ai_companion_daily_limit) == var.ai_companion_daily_limit
    error_message = "Use a non-negative whole number."
  }
}

variable "ai_companion_wait" {
  type        = string
  description = "How long a post waits for a member's comment before a companion answers, e.g. 10m."
  default     = "10m"
  validation {
    condition     = can(regex("^[1-9][0-9]*[smh]$", var.ai_companion_wait))
    error_message = "Use a duration such as 30s, 10m or 1h."
  }
}

variable "web_domain" {
  type        = string
  description = "Custom domain for the web, e.g. new.example.com. Empty keeps the run.app URL. The deployer must be a verified owner of the domain."
  default     = ""
  validation {
    condition     = var.web_domain == "" || can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.web_domain))
    error_message = "Use a bare lowercase host name without scheme, port or path."
  }
}

variable "kakao_client_id" {
  type    = string
  default = ""
}

variable "google_client_id" {
  type    = string
  default = ""
}
