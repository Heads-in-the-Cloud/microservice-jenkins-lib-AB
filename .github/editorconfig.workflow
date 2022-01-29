workflow "PR Audit" {
  on = "pull_request"
  resolves = ["EditorConfig Audit"]
}

action "EditorConfig Audit" {
  uses = "zbeekman/EditorConfig-Action@v1.1.1"
  # secrets = ["GITHUB_TOKEN"] # Will be needed for fixing errors
}
