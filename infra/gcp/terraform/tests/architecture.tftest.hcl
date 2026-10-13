mock_provider "google" {}

variables {
  project_id = "ogu-test-project"
}

run "infrastructure_only" {
  command = plan

  assert {
    condition     = google_compute_instance.api.allow_stopping_for_update && google_compute_instance.api.boot_disk[0].initialize_params[0].size == 20
    error_message = "VM must support resizing and have room for swap/container images."
  }
  assert {
    condition     = google_compute_instance.api.machine_type == "e2-small"
    error_message = "Minimum-cost default must be e2-small."
  }
  assert {
    condition     = google_compute_disk.data.size == 20 && google_compute_instance.api.attached_disk[0].mode == "READ_WRITE"
    error_message = "DB requires a separate persistent data disk."
  }
  assert {
    condition     = google_compute_instance.api.deletion_protection
    error_message = "VM deletion must be explicitly enabled by the operator."
  }
  assert {
    condition     = toset(one(google_compute_firewall.web.allow).ports) == toset(["80", "443"])
    error_message = "Only HTTPS/HTTP may be public."
  }
  assert {
    condition     = google_compute_firewall.ssh.source_ranges == toset(["35.235.240.0/20"])
    error_message = "SSH must be reachable only through IAP."
  }
  assert {
    condition     = length(google_cloud_run_v2_service.web) == 0
    error_message = "No placeholder web should deploy before a real image exists."
  }
  assert {
    condition     = length(google_secret_manager_secret.config) == 3
    error_message = "Create only secret containers; never manage secret values in Terraform."
  }
}

run "ai_companion_ships_off" {
  command = apply
  assert {
    condition     = !jsondecode(google_compute_instance.api.metadata["deployment-config"]).ai_companion_enabled && jsondecode(google_compute_instance.api.metadata["deployment-config"]).ai_companion_daily_limit == 30
    error_message = "AI companions must ship switched off with the agreed daily limit."
  }
}

run "ai_companion_can_be_turned_on" {
  command = apply
  variables {
    ai_companion_enabled     = true
    ai_companion_daily_limit = 10
    ai_companion_wait        = "30m"
  }
  assert {
    condition     = jsondecode(google_compute_instance.api.metadata["deployment-config"]).ai_companion_enabled && jsondecode(google_compute_instance.api.metadata["deployment-config"]).ai_companion_wait == "30m"
    error_message = "The operator's AI companion settings must reach the VM."
  }
}

run "web_scales_to_zero" {
  command = apply
  variables {
    web_image           = "us-central1-docker.pkg.dev/ogu-test-project/ogu/web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    kakao_login_enabled = false
  }
  assert {
    condition = !jsondecode(google_compute_instance.api.metadata["deployment-config"]).kakao_login_enabled && one([
      for item in google_cloud_run_v2_service.web[0].template[0].containers[0].env : item.value
      if item.name == "KAKAO_LOGIN_ENABLED"
    ]) == "false"
    error_message = "Google-only login must disable Kakao consistently in API and web."
  }
  assert {
    condition     = google_cloud_run_v2_service.web[0].template[0].scaling[0].min_instance_count == 0 && google_cloud_run_v2_service.web[0].template[0].scaling[0].max_instance_count == 1
    error_message = "Web must scale to zero and have a maximum of one instance."
  }
  assert {
    condition     = google_cloud_run_v2_service.web[0].template[0].containers[0].resources[0].cpu_idle
    error_message = "Web must use request-based CPU allocation."
  }
}

run "web_keeps_run_app_origin_without_a_domain" {
  command = apply
  variables {
    web_image = "us-central1-docker.pkg.dev/ogu-test-project/ogu/web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
  assert {
    condition     = length(google_cloud_run_domain_mapping.web) == 0 && endswith(output.web_origin, ".us-central1.run.app")
    error_message = "Without a domain the web must keep its run.app origin and map nothing."
  }
}

run "web_domain_becomes_the_origin_everywhere" {
  command = apply
  variables {
    web_image  = "us-central1-docker.pkg.dev/ogu-test-project/ogu/web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    web_domain = "new.example.com"
  }
  assert {
    condition = jsondecode(google_compute_instance.api.metadata["deployment-config"]).web_origin == "https://new.example.com" && one([
      for item in google_cloud_run_v2_service.web[0].template[0].containers[0].env : item.value
      if item.name == "APP_ORIGIN"
    ]) == "https://new.example.com"
    error_message = "API and web must agree on the custom origin, or OAuth and SSE reject each other."
  }
  assert {
    condition     = google_cloud_run_domain_mapping.web[0].name == "new.example.com" && google_cloud_run_domain_mapping.web[0].spec[0].route_name == "ogu-web"
    error_message = "The domain must be mapped to the web service."
  }
}

run "web_domain_rejects_a_url" {
  command = plan
  variables {
    web_domain = "https://new.example.com"
  }
  expect_failures = [var.web_domain]
}
