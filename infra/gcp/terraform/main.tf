locals {
  labels = { app = "ogu", environment = "production" }
  services = toset([
    "compute.googleapis.com", "run.googleapis.com", "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com", "cloudbuild.googleapis.com", "iam.googleapis.com",
    "iap.googleapis.com", "storage.googleapis.com", "logging.googleapis.com",
  ])
  secret_ids = {
    api_env     = "ogu-api-env"
    bff_key     = "ogu-bff-key"
    oauth_state = "ogu-oauth-state"
  }
  web_origin = "https://ogu-web-${data.google_project.current.number}.${var.region}.run.app"
  api_domain = "api.${replace(google_compute_address.api.address, ".", "-")}.sslip.io"
  registry   = "${var.region}-docker.pkg.dev/${var.project_id}/ogu"
  deployment_config = {
    project_id    = var.project_id
    region        = var.region
    api_image     = var.api_image
    api_domain    = local.api_domain
    web_origin    = local.web_origin
    registry      = "${var.region}-docker.pkg.dev"
    backup_bucket = google_storage_bucket.backups.name
    api_secret    = local.secret_ids.api_env
    bff_secret    = local.secret_ids.bff_key
  }
}

data "google_project" "current" { project_id = var.project_id }

resource "google_project_service" "enabled" {
  for_each           = local.services
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_compute_network" "ogu" {
  name                    = "ogu"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.enabled]
}

resource "google_compute_subnetwork" "ogu" {
  name                     = "ogu"
  region                   = var.region
  network                  = google_compute_network.ogu.id
  ip_cidr_range            = "10.59.0.0/24"
  private_ip_google_access = true
}

resource "google_compute_firewall" "web" {
  name          = "ogu-public-https"
  network       = google_compute_network.ogu.id
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["ogu-api"]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

resource "google_compute_firewall" "ssh" {
  name          = "ogu-iap-ssh"
  network       = google_compute_network.ogu.id
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["ogu-api"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_address" "api" {
  name       = "ogu-api"
  region     = var.region
  depends_on = [google_project_service.enabled]
}

resource "google_service_account" "vm" {
  account_id   = "ogu-api"
  display_name = "Ogu API runtime"
  depends_on   = [google_project_service.enabled]
}

resource "google_service_account" "web" {
  account_id   = "ogu-web"
  display_name = "Ogu web runtime"
  depends_on   = [google_project_service.enabled]
}

resource "google_service_account" "build" {
  account_id   = "ogu-build"
  display_name = "Ogu image builder"
  depends_on   = [google_project_service.enabled]
}

resource "google_project_iam_member" "build_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.build.email}"
}

resource "google_project_iam_member" "build_usage" {
  project = var.project_id
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = "serviceAccount:${google_service_account.build.email}"
}

resource "google_artifact_registry_repository" "ogu" {
  location      = var.region
  repository_id = "ogu"
  format        = "DOCKER"
  labels        = local.labels
  depends_on    = [google_project_service.enabled]
}

resource "google_artifact_registry_repository_iam_member" "vm_reader" {
  location   = var.region
  repository = google_artifact_registry_repository.ogu.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_artifact_registry_repository_iam_member" "builder" {
  location   = var.region
  repository = google_artifact_registry_repository.ogu.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.build.email}"
}

resource "google_storage_bucket" "builds" {
  name                        = "${var.project_id}-ogu-builds"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = local.labels
  lifecycle_rule {
    condition { age = 7 }
    action { type = "Delete" }
  }
  depends_on = [google_project_service.enabled]
}

resource "google_storage_bucket_iam_member" "build_source" {
  bucket = google_storage_bucket.builds.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.build.email}"
}

resource "google_storage_bucket" "backups" {
  name                        = "${var.project_id}-ogu-backups"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = local.labels
  lifecycle_rule {
    condition { age = 14 }
    action { type = "Delete" }
  }
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.enabled]
}

resource "google_storage_bucket_iam_member" "backup_writer" {
  bucket = google_storage_bucket.backups.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_secret_manager_secret" "config" {
  for_each  = local.secret_ids
  secret_id = each.value
  labels    = local.labels
  replication {
    auto {}
  }
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.enabled]
}

resource "google_secret_manager_secret_iam_member" "vm" {
  for_each  = toset(["api_env", "bff_key"])
  secret_id = google_secret_manager_secret.config[each.value].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.vm.email}"
}

resource "google_secret_manager_secret_iam_member" "web" {
  for_each  = toset(["bff_key", "oauth_state"])
  secret_id = google_secret_manager_secret.config[each.value].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.web.email}"
}

resource "google_compute_disk" "data" {
  name   = "ogu-data"
  zone   = var.zone
  type   = "pd-standard"
  size   = 20
  labels = local.labels
  lifecycle { prevent_destroy = true }
  depends_on = [google_project_service.enabled]
}

resource "google_compute_instance" "api" {
  name                      = "ogu-api"
  zone                      = var.zone
  machine_type              = var.machine_type
  allow_stopping_for_update = true
  tags                      = ["ogu-api"]
  labels                    = local.labels
  deletion_protection       = true
  boot_disk {
    initialize_params {
      image = var.boot_image
      size  = 20
      type  = "pd-standard"
    }
  }
  attached_disk {
    source      = google_compute_disk.data.id
    device_name = "ogu-data"
    mode        = "READ_WRITE"
  }
  network_interface {
    subnetwork = google_compute_subnetwork.ogu.id
    access_config { nat_ip = google_compute_address.api.address }
  }
  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }
  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }
  metadata = {
    enable-oslogin         = "TRUE"
    block-project-ssh-keys = "TRUE"
    startup-script         = file("${path.module}/../vm/bootstrap.sh")
    deployment-config      = jsonencode(local.deployment_config)
    compose-base64         = filebase64("${path.module}/../vm/compose.yaml")
    caddy-base64           = filebase64("${path.module}/../../Caddyfile")
    runtime-base64         = filebase64("${path.module}/../vm/runtime.py")
    backup-base64          = filebase64("${path.module}/../vm/backup.py")
  }
  lifecycle {
    precondition {
      condition     = startswith(var.zone, "${var.region}-")
      error_message = "Zone must be in the chosen region."
    }
  }
  depends_on = [google_secret_manager_secret_iam_member.vm, google_artifact_registry_repository_iam_member.vm_reader]
}
