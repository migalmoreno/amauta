(ns blog.ssg.builders.github
  "Mirrors project repos to GitHub via the gh CLI."
  (:require
   [babashka.process :as proc]))

(defn setup-git-auth!
  "Configure git to authenticate to GitHub via the gh CLI (GH_TOKEN)."
  []
  (proc/shell "gh" "auth" "setup-git"))

(defn update-profile!
  "Set the authenticated GitHub account's display name and external link."
  [fullname domain]
  (proc/shell "gh"
              "api"
              "--method"
              "PATCH"
              "user"
              "-f"
              (str "name=" fullname)
              "-f"
              (str "blog=https://" domain)))

(defn- repo-exists?
  [owner repo-name]
  (-> @(proc/process ["gh" "repo" "view" (str owner "/" repo-name)]
                     {:out :string :err :string})
      :exit
      zero?))

(defn- create-repo!
  [owner repo-name]
  (proc/shell "gh" "repo" "create" (str owner "/" repo-name) "--public"))

(defn- sync-settings!
  [owner repo-name synopsis actions?]
  (proc/shell "gh"
              "repo"
              "edit"
              (str owner "/" repo-name)
              "--description"
              synopsis
              "--enable-issues=false"
              "--enable-wiki=false"
              "--enable-projects=false")
  (proc/shell "gh"
              "api"
              "--method"
              "PUT"
              (str "repos/" owner "/" repo-name "/actions/permissions")
              "-F"
              (str "enabled=" (boolean actions?)))
  (proc/shell "gh"
              "api"
              "--method"
              "PATCH"
              (str "repos/" owner "/" repo-name)
              "-F"
              "has_pull_requests=false"))

(defn ensure-repo!
  "Create owner/repo-name on GitHub if it doesn't exist, then bring its
  description, issues, wiki, projects, and Actions settings in sync.
  actions? controls whether GitHub Actions is enabled for the repo."
  [owner repo-name synopsis actions?]
  (when-not (repo-exists? owner repo-name)
    (create-repo! owner repo-name))
  (sync-settings! owner repo-name synopsis actions?))

(defn push-mirror!
  "Push the bare mirror at git-dir to owner/repo-name on GitHub."
  [git-dir owner repo-name]
  (proc/shell "git"
              "-C"
              git-dir
              "push"
              "--mirror"
              (str "https://github.com/" owner "/" repo-name ".git")))
