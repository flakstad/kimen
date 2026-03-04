#!/usr/bin/env bb
(ns scripts.migrate-go-vault
  (:require [kimen.migration.go-vault :as migrate]))

(System/exit (int (migrate/main! *command-line-args*)))
