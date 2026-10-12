resource "google_cloud_run_v2_service" "web" {
  count               = var.web_image == "" ? 0 : 1
  name                = "ogu-web"
  location            = var.region
  deletion_protection = true
  ingress             = "INGRESS_TRAFFIC_ALL"
  labels              = local.labels
  scaling {
    min_instance_count = 0
    max_instance_count = 1
  }
  template {
    service_account                  = google_service_account.web.email
    timeout                          = "60s"
    max_instance_request_concurrency = 40
    scaling {
      min_instance_count = 0
      max_instance_count = 1
    }
    containers {
      image = var.web_image
      ports { container_port = 8080 }
      resources {
        limits            = { cpu = "1", memory = "512Mi" }
        cpu_idle          = true
        startup_cpu_boost = false
      }
      dynamic "env" {
        for_each = {
          NODE_ENV            = "production"
          APP_ENV             = "production"
          API_ORIGIN          = "https://${local.api_domain}"
          SSE_PUBLIC_ORIGIN   = "https://${local.api_domain}"
          APP_ORIGIN          = local.web_origin
          KAKAO_CLIENT_ID     = var.kakao_client_id
          KAKAO_LOGIN_ENABLED = tostring(var.kakao_login_enabled)
          GOOGLE_CLIENT_ID    = var.google_client_id
        }
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = { BFF_API_KEY = "bff_key", OAUTH_STATE_SECRET = "oauth_state" }
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.config[env.value].secret_id
              version = "latest"
            }
          }
        }
      }
      startup_probe {
        initial_delay_seconds = 0
        timeout_seconds       = 1
        period_seconds        = 3
        failure_threshold     = 30
        tcp_socket { port = 8080 }
      }
    }
  }
  # Releases own only this field; Terraform continues to own runtime and IAM.
  # Initial creation still uses web_image. No infrastructure apply is in this PR.
  lifecycle {
    ignore_changes = [template[0].containers[0].image]
  }
  depends_on = [google_secret_manager_secret_iam_member.web]
}

resource "google_cloud_run_v2_service_iam_member" "public" {
  count    = var.web_image == "" ? 0 : 1
  location = var.region
  name     = google_cloud_run_v2_service.web[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
