(ns amauta.ssg.builders.publish
  (:require
   [babashka.process :as proc]
   [clojure.string :as str]))

(def ^:private public-branch "public")

(defn- git-out
  [git-dir & args]
  (let [{:keys [exit out]} @(proc/process (into ["git" "-C" git-dir] args)
                                          {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn squash-unpublished!
  "Ensure git-dir's `public` branch has a commit whose tree matches the
  current tip of the branch you actually develop on, squashing any commits
  made since the last publish into one commit dated now. Force-pushes
  `public` to repo-url so the boundary survives across cache clears, then
  repoints git-dir's local branch ref at that (possibly new) public commit
  so every downstream consumer of git-dir - archive extraction, the
  dumb-HTTP git server, the commit browser, and the GitHub mirror -
  transparently serves the squashed history under the branch's real name,
  without that branch's history in Forgejo ever being touched. The commit's
  author and committer identity are set explicitly to author-name/
  author-email rather than left to ambient git config, since the CI
  environment this runs in starts with none configured."
  [git-dir repo-url author-name author-email]
  (let [branch (git-out git-dir "symbolic-ref" "--short" "HEAD")
        branch-tree (git-out git-dir "rev-parse" (str branch "^{tree}"))
        public-tip (git-out git-dir
                            "rev-parse" "--verify"
                            "-q"
                            (str "refs/heads/" public-branch))
        public-tree (when public-tip
                      (git-out git-dir "rev-parse" (str public-tip "^{tree}")))
        tip (if (= branch-tree public-tree)
              public-tip
              (let [now         (str (java.time.OffsetDateTime/now))
                    parent-args (if public-tip ["-p" public-tip] [])
                    commit-args (concat ["git" "-C" git-dir "commit-tree"
                                         branch-tree]
                                        parent-args
                                        ["-m" "Publish"])
                    commit      (str/trim
                                 (:out @(proc/process
                                         commit-args
                                         {:out       :string
                                          :err       :string
                                          :extra-env {"GIT_AUTHOR_NAME"     author-name
                                                      "GIT_AUTHOR_EMAIL"    author-email
                                                      "GIT_COMMITTER_NAME"  author-name
                                                      "GIT_COMMITTER_EMAIL" author-email
                                                      "GIT_AUTHOR_DATE"     now
                                                      "GIT_COMMITTER_DATE"  now}})))]
                (proc/shell "git"
                            "-C"
                            git-dir
                            "update-ref"
                            (str "refs/heads/" public-branch)
                            commit)
                (proc/shell "git"
                            "-C"
                            git-dir
                            "push"
                            "--force"
                            repo-url
                            (str "refs/heads/"  public-branch
                                 ":refs/heads/" public-branch))
                commit))]
    (proc/shell "git"
                "-C"
                git-dir
                "update-ref"
                (str "refs/heads/" branch)
                tip)))
