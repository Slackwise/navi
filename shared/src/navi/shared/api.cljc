(ns navi.shared.api
  "HTTP API contract shared by the backend Worker and its clients.")

(def routes
  {:interactions "/interactions"
   :auth-google "/auth/google"
   :auth-me "/auth/me"
   :auth-logout "/auth/logout"
   :auth-logout-all "/auth/logout-all"})
