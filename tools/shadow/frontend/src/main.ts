import { StrictMode } from "react";
import { createElement } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./style.css";

createRoot(document.getElementById("app")!).render(
  createElement(StrictMode, null, createElement(App)),
);
