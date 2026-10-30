/* Only shipped scripts run; Markdown is never evaluated as JavaScript. No native bridge. */
(function () {
    "use strict";
    window.__xnotesScienceReady = false;
    window.__xnotesScienceStopped = false;
    window.__xnotesDiagramFallback = function (host) {
        if (host.dataset.complete) return;
        host.classList.add("science-error");
        host.querySelector("summary").textContent = host.dataset.error;
        host.querySelector("details").setAttribute("open", "");
    };
    window.__xnotesScienceFinish = function () {
        window.__xnotesScienceStopped = true;
        clearTimeout(window.__xnotesScienceTimer);
        document.querySelectorAll("figure.md-diagram").forEach(window.__xnotesDiagramFallback);
        window.__xnotesScienceReady = true;
    };
    window.__xnotesScienceTimer = setTimeout(window.__xnotesScienceFinish, 8000);
    function load(name, next) {
        const script = document.createElement("script");
        script.src = "https://xnotes-markdown.invalid/science/" + name;
        script.onerror = window.__xnotesScienceFinish;
        if (next) script.onload = next;
        document.head.appendChild(script);
    }
    load("mermaid.min.js", function () {
        if (!window.mermaid) { window.__xnotesScienceFinish(); return; }
        load("diagrams.js");
    });
}());
