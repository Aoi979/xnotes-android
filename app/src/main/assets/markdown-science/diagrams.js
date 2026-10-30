/* Xnotes Markdown adapter. Mermaid itself is the unmodified, pinned upstream bundle. */
(async function () {
    "use strict";
    const hosts = Array.from(document.querySelectorAll("figure.md-diagram"));
    const stopped = () => window.__xnotesScienceStopped;
    function externalUrl(text) {
        const pattern = /url\s*\(([^)]*)\)/gi;
        let match;
        while ((match = pattern.exec(text)) !== null) {
            if (!match[1].trim().replace(/^["']|["']$/g, "").startsWith("#")) return true;
        }
        return false;
    }
    try {
        if (!window.mermaid) throw new Error("Renderer unavailable");
        const colors = JSON.parse(document.querySelector('meta[name="xnotes-diagram-colors"]').content);
        mermaid.initialize({
            startOnLoad: false, securityLevel: "strict", suppressErrorRendering: true,
            maxTextSize: 16384, maxEdges: 200, htmlLabels: false,
            // Document directives cannot loosen any of these settings. Configuration in
            // diagrams is also refused below, before the upstream parser is entered.
            secure: ["secure", "securityLevel", "startOnLoad", "maxTextSize", "maxEdges",
                "suppressErrorRendering", "htmlLabels", "theme", "themeCSS", "themeVariables", "flowchart"],
            theme: "base", themeVariables: colors,
            flowchart: { htmlLabels: false }, class: { htmlLabels: false }
        });
        let characters = 0;
        let outputSize = 0;
        for (let index = 0; index < hosts.length && !stopped(); index++) {
            const host = hosts[index];
            const source = host.querySelector("pre code").textContent;
            characters += source.length;
            try {
                if (index >= 16 || source.length > 16384 || characters > 65536)
                    throw new Error("Diagram budget exceeded");
                if (/%%\s*\{/.test(source) || /^\s*---\s*(?:\r?\n|$)/.test(source))
                    throw new Error("Document configuration refused");
                const result = await mermaid.render("xnotes-diagram-" + index, source);
                if (stopped()) break;
                const parsed = new DOMParser().parseFromString(result.svg, "image/svg+xml");
                const svg = parsed.documentElement;
                if (svg.localName !== "svg" || svg.namespaceURI !== "http://www.w3.org/2000/svg" || parsed.querySelector("parsererror, foreignObject, image"))
                    throw new Error("Unsupported diagram content");
                // The final page is static SVG. No callbacks or links from Mermaid are bound.
                svg.querySelectorAll("script, iframe, object, embed, a, animate, animateMotion, animateTransform, set").forEach(node => {
                    while (node.firstChild && node.localName === "a") node.parentNode.insertBefore(node.firstChild, node);
                    node.remove();
                });
                [svg, ...svg.querySelectorAll("*")].forEach(node => {
                    Array.from(node.attributes).forEach(attr => {
                        if (/^on/i.test(attr.name) || (/href$/i.test(attr.name) && !attr.value.startsWith("#")) ||
                            externalUrl(attr.value))
                            node.removeAttribute(attr.name);
                    });
                });
                svg.querySelectorAll("style").forEach(style => {
                    if (/@import/i.test(style.textContent) || externalUrl(style.textContent)) style.remove();
                });
                const box = svg.viewBox.baseVal;
                if (!(box.width > 0 && box.height > 0) || box.width > 16384 || box.height > 16384 || box.width * box.height > 16777216)
                    throw new Error("Diagram dimensions exceeded");
                svg.setAttribute("width", box.width); svg.setAttribute("height", box.height);
                svg.style.removeProperty("max-width");
                const frozen = parsed.createElementNS("http://www.w3.org/2000/svg", "style");
                frozen.textContent = "*{animation:none!important;transition:none!important}";
                svg.appendChild(frozen);
                const serialized = new XMLSerializer().serializeToString(svg);
                outputSize += serialized.length;
                if (serialized.length > 1048576 || outputSize > 4194304) throw new Error("Diagram output exceeded");
                // An image document isolates even diagram CSS from the reader's DOM.
                const image = new Image(); image.className = "diagram-image";
                image.width = Math.ceil(box.width); image.height = Math.ceil(box.height);
                image.alt = (svg.querySelector("title") || {}).textContent || "Mermaid";
                const decoded = new Promise((resolve, reject) => { image.onload = resolve; image.onerror = reject; });
                image.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(serialized);
                await decoded;
                if (stopped()) break;
                const drawing = document.createElement("div"); drawing.className = "diagram-drawing";
                drawing.appendChild(image);
                host.insertBefore(drawing, host.firstChild);
                host.querySelector("details").removeAttribute("open");
                host.dataset.complete = "true";
            } catch (_) { window.__xnotesDiagramFallback(host); }
        }
    } catch (_) { /* Loading or unsupported WebView: retain readable source for every block. */ }
    finally { window.__xnotesScienceFinish(); }
}());
