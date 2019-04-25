(def version "0.6.0-SNAPSHOT")
(def repo "https://github.com/LuxLang/lux")
(def sonatype-releases "https://oss.sonatype.org/service/local/staging/deploy/maven2/")
(def sonatype-snapshots "https://oss.sonatype.org/content/repositories/snapshots/")

(defproject com.github.luxlang/lux-lua #=(identity version)
  :description "A Lua compiler for Lux."
  :url ~repo
  :license {:name "Lux License v0.1"
            :url ~(str repo "/blob/master/license.txt")}
  :scm {:name "git"
        :url ~(str repo ".git")}
  :pom-addition [:developers [:developer
                              [:name "Eduardo Julian"]
                              [:url "https://github.com/eduardoejp"]]]
  
  :repositories [["releases" ~sonatype-releases]
                 ["snapshots" ~sonatype-snapshots]]
  :deploy-repositories [["releases" {:url ~sonatype-releases :creds :gpg}]
                        ["snapshots" {:url ~sonatype-snapshots :creds :gpg}]]
  
  :plugins [[com.github.luxlang/lein-luxc ~version]]
  :dependencies [[com.github.luxlang/luxc-jvm ~version]
                 [com.github.luxlang/stdlib ~version]
                 [net.sandius.rembulan/rembulan-runtime "0.1-SNAPSHOT"]
                 [net.sandius.rembulan/rembulan-stdlib "0.1-SNAPSHOT"]
                 [net.sandius.rembulan/rembulan-compiler "0.1-SNAPSHOT"]]

  :manifest {"lux" ~version}
  :source-paths ["source"]
  :lux {:program "program"}
  )
