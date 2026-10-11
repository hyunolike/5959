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

variable "kakao_client_id" {
  type    = string
  default = ""
}

variable "google_client_id" {
  type    = string
  default = ""
}
