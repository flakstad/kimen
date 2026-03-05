(ns kimen.commands.completion
  (:require
   [clojure.string :as str]))

(def usage
  (str/join
   "\n"
   ["kimen completion"
    ""
    "Usage:"
    "  kimen completion <bash|zsh|fish|powershell>"
    ""]))

(def supported-shells
  ["bash" "zsh" "fish" "powershell"])

(defn supported-shells-str
  []
  (str/join ", " supported-shells))

(def ^:private completion-top-level-commands
  ["version"
   "config"
   "vault"
   "secret"
   "remote"
   "sync"
   "bundle"
   "map"
   "plan"
   "run"
   "render"
   "envfile"
   "doctor"
   "init"
   "project"
   "completion"
   "help"])

(def ^:private completion-subcommands
  {"bundle" ["keygen" "recipient" "seal" "open"]
   "config" ["path" "show" "vault" "unlock"]
   "doctor" []
   "envfile" []
   "init" ["ci-pr-safety" "ci-deploy" "ci-sync-gate"]
   "map" ["lint"]
   "plan" []
   "project" ["run" "render" "plan"]
   "remote" ["add" "get" "set" "list" "rm"]
   "render" []
   "run" []
   "secret" ["set" "list" "get" "rm" "mv"]
   "sync" ["init" "preflight" "status" "conflicts" "changes" "push" "pull" "resolve" "reset-baseline" "unlock" "restore"]
   "vault" ["init" "info" "path" "rekey"]
   "version" []})

(defn- completion-words
  [xs]
  (str/join " " xs))

(defn- bash-completion-script
  []
  (let [top (completion-words completion-top-level-commands)
        cases (apply str
                     (for [cmd (sort (keys completion-subcommands))
                           :let [subs (completion-words (get completion-subcommands cmd))]
                           :when (not (str/blank? subs))]
                       (format "    %s) COMPREPLY=( $(compgen -W '%s' -- \"$cur\") ) ;;\n"
                               cmd
                               subs)))]
    (str "_kimen_completion() {\n"
         "  local cur cmd\n"
         "  cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
         "  if [[ ${COMP_CWORD} -eq 1 ]]; then\n"
         (format "    COMPREPLY=( $(compgen -W '%s' -- \"$cur\") )\n" top)
         "    return 0\n"
         "  fi\n"
         "  cmd=\"${COMP_WORDS[1]}\"\n"
         "  case \"$cmd\" in\n"
         cases
         "  esac\n"
         "}\n"
         "complete -F _kimen_completion kimen\n")))

(defn- zsh-completion-script
  []
  (str "#compdef kimen\n"
       "autoload -U +X bashcompinit && bashcompinit\n"
       (bash-completion-script)))

(defn- fish-completion-script
  []
  (let [top (completion-words completion-top-level-commands)
        lines (for [cmd (sort (keys completion-subcommands))
                    :let [subs (completion-words (get completion-subcommands cmd))]
                    :when (not (str/blank? subs))]
                (format "complete -c kimen -f -n '__fish_seen_subcommand_from %s' -a '%s'"
                        cmd
                        subs))]
    (str (format "complete -c kimen -f -n '__fish_use_subcommand' -a '%s'\n" top)
         (str/join "\n" lines)
         "\n")))

(defn- powershell-completion-script
  []
  (let [root (->> completion-top-level-commands
                  (map #(format "\"%s\"" %))
                  (str/join ", "))
        table-lines (for [cmd (sort (keys completion-subcommands))
                          :let [subs (get completion-subcommands cmd)]
                          :when (seq subs)]
                      (str "  \"" cmd "\" = @(" (str/join ", " (map #(format "\"%s\"" %) subs)) ")\n"))]
    (str "$kimenCommands = @{\n"
         "  \"__root__\" = @(" root ")\n"
         (apply str table-lines)
         "}\n"
         "\n"
         "Register-ArgumentCompleter -Native -CommandName kimen -ScriptBlock {\n"
         "  param($wordToComplete, $commandAst, $cursorPosition)\n"
         "  $parts = @($commandAst.CommandElements | ForEach-Object { $_.Extent.Text })\n"
         "  if ($parts.Count -le 1) {\n"
         "    $candidates = $kimenCommands[\"__root__\"]\n"
         "  } else {\n"
         "    $cmd = $parts[1]\n"
         "    if ($parts.Count -le 2 -and $kimenCommands.ContainsKey($cmd)) {\n"
         "      $candidates = $kimenCommands[$cmd]\n"
         "    } else {\n"
         "      $candidates = @()\n"
         "    }\n"
         "  }\n"
         "  foreach ($candidate in $candidates) {\n"
         "    if ($candidate -like \"$wordToComplete*\") {\n"
         "      [System.Management.Automation.CompletionResult]::new($candidate, $candidate, 'ParameterValue', $candidate)\n"
         "    }\n"
         "  }\n"
         "}\n")))

(defn script-for-shell
  [shell]
  (case shell
    "bash" (bash-completion-script)
    "zsh" (zsh-completion-script)
    "fish" (fish-completion-script)
    "powershell" (powershell-completion-script)
    nil))
