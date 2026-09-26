import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

async function boot() {
  // Development only: `?mock` swaps in a simulated phone and backend so the UI
  // can be worked on in a plain browser. The whole branch is compiled out of
  // production builds.
  if (import.meta.env.DEV && new URLSearchParams(location.search).has("mock")) {
    const { installMockRuntime } = await import("./dev/mockRuntime");
    installMockRuntime(new URLSearchParams(location.search).get("mock") || "streaming");
  }
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}

void boot();
