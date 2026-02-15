document.addEventListener('DOMContentLoaded', () => {
    // Elements
    const chatTitleElement = document.getElementById('chat-title');
    const chatAreaElement = document.getElementById('chat-area');
    const dirRtlBtn = document.getElementById('dir-rtl');
    const dirAutoBtn = document.getElementById('dir-auto');
    const dirLtrBtn = document.getElementById('dir-ltr');
    const systemPromptBtn = document.getElementById('system-prompt-btn');
    const systemPromptOverlay = document.getElementById('system-prompt-overlay');
    const systemPromptContent = document.getElementById('system-prompt-content');
    const systemPromptClose = document.getElementById('system-prompt-close');

    // State
    let currentDirectionMode = 'auto'; // 'ltr', 'rtl', 'auto'
    let chatData = null;
    let currentVariantPath = [];

    // Config
    const SHARE_API_BASE = 'https://api-divonr.xyz/share';

    // Check if we're loading from a shared link
    const urlParams = new URLSearchParams(window.location.search);
    const shareId = urlParams.get('id');
    const encryptionKey = window.location.hash ? window.location.hash.substring(1).replace('key=', '') : null;

    // Init
    loadChatData();
    updateDirectionButtons();

    // Event Listeners — Direction buttons
    dirRtlBtn.addEventListener('click', () => setDirection('rtl'));
    dirAutoBtn.addEventListener('click', () => setDirection('auto'));
    dirLtrBtn.addEventListener('click', () => setDirection('ltr'));

    // Event Listeners — System Prompt dialog
    systemPromptBtn.addEventListener('click', () => {
        systemPromptOverlay.classList.add('visible');
    });
    systemPromptClose.addEventListener('click', () => {
        systemPromptOverlay.classList.remove('visible');
    });
    systemPromptOverlay.addEventListener('click', (e) => {
        if (e.target === systemPromptOverlay) {
            systemPromptOverlay.classList.remove('visible');
        }
    });

    // ============================
    // Data Loading
    // ============================

    async function loadChatData() {
        try {
            let jsonData;

            if (shareId) {
                // Remote encrypted mode
                if (!encryptionKey) {
                    throw new Error('MISSING_KEY');
                }

                // Fetch encrypted data from server
                const response = await fetch(`${SHARE_API_BASE}/${shareId}`);
                if (!response.ok) {
                    if (response.status === 404) {
                        throw new Error('NOT_FOUND');
                    }
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                const encryptedBase64 = await response.text();

                // Decrypt using CryptoJS
                try {
                    // Parse the hex key into a CryptoJS WordArray
                    const keyWordArray = CryptoJS.enc.Hex.parse(encryptionKey);

                    // The encrypted data is Base64(IV + ciphertext)
                    const rawData = CryptoJS.enc.Base64.parse(encryptedBase64);
                    const rawBytes = [];
                    for (let i = 0; i < rawData.sigBytes; i++) {
                        rawBytes.push((rawData.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff);
                    }

                    // Extract IV (first 16 bytes) and ciphertext (rest)
                    const ivBytes = rawBytes.slice(0, 16);
                    const ciphertextBytes = rawBytes.slice(16);

                    const iv = CryptoJS.lib.WordArray.create(new Uint8Array(ivBytes));
                    const ciphertext = CryptoJS.lib.WordArray.create(new Uint8Array(ciphertextBytes));

                    const cipherParams = CryptoJS.lib.CipherParams.create({
                        ciphertext: ciphertext
                    });

                    const decrypted = CryptoJS.AES.decrypt(cipherParams, keyWordArray, {
                        iv: iv,
                        mode: CryptoJS.mode.CBC,
                        padding: CryptoJS.pad.Pkcs7
                    });

                    const decryptedText = decrypted.toString(CryptoJS.enc.Utf8);
                    if (!decryptedText) {
                        throw new Error('DECRYPT_FAILED');
                    }

                    jsonData = JSON.parse(decryptedText);
                } catch (decryptError) {
                    console.error('Decryption error:', decryptError);
                    throw new Error('DECRYPT_FAILED');
                }
            } else {
                // Local file mode (fallback)
                const response = await fetch('chat.json');
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                jsonData = await response.json();
            }

            chatData = jsonData;

            if (chatData.messageNodes && chatData.messageNodes.length > 0) {
                currentVariantPath = chatData.currentVariantPath
                    ? [...chatData.currentVariantPath]
                    : buildDefaultVariantPath(chatData.messageNodes);
            }

            // Set system prompt content
            if (chatData.systemPrompt && chatData.systemPrompt.trim()) {
                systemPromptContent.textContent = chatData.systemPrompt;
                systemPromptBtn.classList.add('has-prompt');
            } else {
                systemPromptContent.textContent = 'אין סיסטם פרומפט';
                systemPromptBtn.classList.remove('has-prompt');
            }

            renderChat();
        } catch (error) {
            console.error('Error loading chat data:', error);
            let errorMessage;
            if (error.message === 'MISSING_KEY') {
                errorMessage = 'הקישור חסר מפתח הצפנה. ודא שהקישור מלא.';
            } else if (error.message === 'NOT_FOUND') {
                errorMessage = 'הקישור חסר או הוסר.';
            } else if (error.message === 'DECRYPT_FAILED') {
                errorMessage = 'הקישור חסר או הוסר.';
            } else if (shareId) {
                errorMessage = `שגיאה בטעינת השיחה: ${error.message}`;
            } else {
                errorMessage = `Error loading chat history.<br>Please ensure <code>chat.json</code> exists in the same directory.<br><small>${error.message}</small>`;
            }
            chatAreaElement.innerHTML = `<div style="text-align: center; color: var(--accent-red); margin-top: 20px;">
                ${errorMessage}
            </div>`;
        }
    }

    // ============================
    // Branching Logic
    // ============================

    function buildDefaultVariantPath(nodes) {
        if (!nodes || nodes.length === 0) return [];
        const path = [];
        let currentNode = nodes.find(n => !n.parentNodeId);
        if (!currentNode && nodes.length > 0) currentNode = nodes[0];

        while (currentNode) {
            if (currentNode.variants && currentNode.variants.length > 0) {
                const variant = currentNode.variants[0];
                path.push(variant.variantId);
                if (variant.childNodeId) {
                    currentNode = nodes.find(n => n.nodeId === variant.childNodeId);
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return path;
    }

    function buildMessagesFromPath(nodes, variantPath) {
        const messages = [];
        for (const variantId of variantPath) {
            for (const node of nodes) {
                const variant = node.variants.find(v => v.variantId === variantId);
                if (variant) {
                    const userMsg = { ...variant.userMessage, _nodeId: node.nodeId, _variantId: variant.variantId };
                    messages.push(userMsg);
                    if (variant.responses) {
                        variant.responses.forEach(r => messages.push(r));
                    }
                    break;
                }
            }
        }
        return messages;
    }

    function getBranchInfo(nodeId) {
        if (!chatData || !chatData.messageNodes) return null;
        const node = chatData.messageNodes.find(n => n.nodeId === nodeId);
        if (!node || node.variants.length <= 1) return null;

        const currentVarId = currentVariantPath.find(vid =>
            node.variants.some(v => v.variantId === vid)
        );
        if (!currentVarId) return null;

        const currentIndex = node.variants.findIndex(v => v.variantId === currentVarId);
        if (currentIndex === -1) return null;

        return {
            nodeId,
            currentVariantIndex: currentIndex,
            totalVariants: node.variants.length,
            currentVariantId: currentVarId,
            hasPrevious: currentIndex > 0,
            hasNext: currentIndex < node.variants.length - 1,
            displayText: `${currentIndex + 1}/${node.variants.length}`
        };
    }

    function switchVariant(nodeId, newVariantIndex) {
        const node = chatData.messageNodes.find(n => n.nodeId === nodeId);
        if (!node) return;
        const newVariant = node.variants[newVariantIndex];
        if (!newVariant) return;

        const pathIndex = currentVariantPath.findIndex(vid =>
            node.variants.some(v => v.variantId === vid)
        );
        if (pathIndex === -1) return;

        const newPath = currentVariantPath.slice(0, pathIndex);
        newPath.push(newVariant.variantId);

        let currentVariant = newVariant;
        while (currentVariant.childNodeId) {
            const childNode = chatData.messageNodes.find(n => n.nodeId === currentVariant.childNodeId);
            if (childNode && childNode.variants && childNode.variants.length > 0) {
                const oldVariantInChild = currentVariantPath.find(vid =>
                    childNode.variants.some(v => v.variantId === vid)
                );
                let childVariant;
                if (oldVariantInChild) {
                    childVariant = childNode.variants.find(v => v.variantId === oldVariantInChild);
                }
                if (!childVariant) {
                    childVariant = childNode.variants[0];
                }
                newPath.push(childVariant.variantId);
                currentVariant = childVariant;
            } else {
                break;
            }
        }

        currentVariantPath = newPath;
        renderChat();
    }

    // ============================
    // Text Direction (mirrors TextDirectionUtils.kt)
    // ============================

    function inferTextDirection(text) {
        if (!text || !text.trim()) return 'ltr';

        for (const char of text) {
            if (!isLetter(char)) continue;

            const code = char.charCodeAt(0);
            if (code >= 0x0590 && code <= 0x05FF) return 'rtl';
            if ((code >= 0x0600 && code <= 0x06FF) ||
                (code >= 0x0750 && code <= 0x077F) ||
                (code >= 0x08A0 && code <= 0x08FF)) return 'rtl';

            return 'ltr';
        }
        return 'ltr';
    }

    function isLetter(char) {
        const code = char.charCodeAt(0);
        if ((code >= 0x41 && code <= 0x5A) || (code >= 0x61 && code <= 0x7A)) return true;
        if (code >= 0x00C0 && code <= 0x024F) return true;
        if (code >= 0x0370 && code <= 0x03FF) return true;
        if (code >= 0x0400 && code <= 0x04FF) return true;
        if (code >= 0x0590 && code <= 0x05FF) return true;
        if (code >= 0x0600 && code <= 0x06FF) return true;
        if (code >= 0x0750 && code <= 0x077F) return true;
        if (code >= 0x08A0 && code <= 0x08FF) return true;
        if (code >= 0x0900 && code <= 0x097F) return true;
        if (code >= 0x4E00 && code <= 0x9FFF) return true;
        if (code >= 0xAC00 && code <= 0xD7AF) return true;
        return false;
    }

    // ============================
    // Rendering
    // ============================

    /**
     * Render the chat. If preserveScroll is true, keep scroll position.
     */
    function renderChat(preserveScroll = false) {
        if (!chatData) return;

        chatTitleElement.innerText = chatData.preview_name || 'Chat Export';
        document.title = chatData.preview_name || 'Chat Export';

        // Save scroll position before re-rendering
        const savedScrollTop = chatAreaElement.scrollTop;

        chatAreaElement.innerHTML = '';

        let messages;
        if (chatData.messageNodes && chatData.messageNodes.length > 0) {
            messages = buildMessagesFromPath(chatData.messageNodes, currentVariantPath);
        } else if (chatData.messages && chatData.messages.length > 0) {
            messages = chatData.messages;
        } else {
            messages = [];
        }

        if (messages.length > 0) {
            messages.forEach(msg => {
                if (msg.role === 'tool_call' || msg.role === 'tool_response') return;
                const messageEl = createMessageElement(msg);
                chatAreaElement.appendChild(messageEl);
            });

            if (preserveScroll) {
                // Restore scroll position
                chatAreaElement.scrollTop = savedScrollTop;
            } else {
                // Initial load — scroll to bottom
                setTimeout(() => {
                    chatAreaElement.scrollTop = chatAreaElement.scrollHeight;
                }, 100);
            }
        } else {
            chatAreaElement.innerHTML = '<div style="text-align: center; opacity: 0.5; margin-top: 20px;">No messages in this chat.</div>';
        }
    }

    function createMessageElement(message) {
        const isUser = message.role === 'user';
        const isAssistant = message.role === 'assistant';

        const rowDiv = document.createElement('div');
        rowDiv.classList.add('message-row');
        rowDiv.classList.add(isUser ? 'user' : (isAssistant ? 'assistant' : 'system'));

        // Assistant Avatar
        if (!isUser) {
            const avatarDiv = document.createElement('div');
            avatarDiv.classList.add('avatar');
            const initial = (message.model || message.role || 'A').charAt(0).toUpperCase();
            avatarDiv.innerText = initial;
            rowDiv.appendChild(avatarDiv);
        }

        const containerDiv = document.createElement('div');
        containerDiv.classList.add('message-container');

        const bubbleDiv = document.createElement('div');
        bubbleDiv.classList.add('message-bubble');

        // 1. Model Name
        if (isAssistant && message.model) {
            const modelNameDiv = document.createElement('div');
            modelNameDiv.classList.add('model-name');
            modelNameDiv.innerText = message.model;
            bubbleDiv.appendChild(modelNameDiv);
        }

        // 2. Thoughts / Thinking
        if (isAssistant && message.thoughtsStatus && message.thoughtsStatus !== 'NONE') {
            const thoughtsDiv = document.createElement('div');
            thoughtsDiv.classList.add('thoughts-container');

            const headerDiv = document.createElement('div');
            headerDiv.classList.add('thoughts-header');
            headerDiv.innerHTML = `
                <span class="thoughts-icon">💡</span>
                <span class="thoughts-title">מחשבות... ${message.thinkingDurationSeconds ? `(${parseFloat(message.thinkingDurationSeconds).toFixed(1)} שניות)` : ''}</span>
                <span class="thoughts-toggle-icon">▼</span>
            `;

            const contentDiv = document.createElement('div');
            contentDiv.classList.add('thoughts-content');

            if (message.thoughtsStatus === 'UNAVAILABLE') {
                contentDiv.innerHTML = '<em>(מחשבות מודל זה לא זמינות)</em>';
            } else {
                contentDiv.innerHTML = parseMarkdown(message.thoughts || '');
            }

            headerDiv.addEventListener('click', () => {
                const isExpanded = contentDiv.classList.toggle('expanded');
                headerDiv.querySelector('.thoughts-toggle-icon').innerText = isExpanded ? '▲' : '▼';
            });

            thoughtsDiv.appendChild(headerDiv);
            thoughtsDiv.appendChild(contentDiv);
            // Apply direction to thoughts header and content
            applyDirToElement(headerDiv);
            applyPerBlockDirection(contentDiv);
            bubbleDiv.appendChild(thoughtsDiv);
        }

        // 3. Attachments
        if (message.attachments && message.attachments.length > 0) {
            message.attachments.forEach(att => {
                const attDiv = document.createElement('div');
                attDiv.classList.add('attachment');

                let icon = '📄';
                if (att.mime_type.startsWith('image/')) icon = '🖼️';
                else if (att.mime_type.startsWith('audio/')) icon = '🎵';
                else if (att.mime_type.startsWith('video/')) icon = '🎥';
                else if (att.mime_type === 'application/pdf') icon = '📕';

                attDiv.innerHTML = `
                    <span class="attachment-icon">${icon}</span>
                    <span class="attachment-name">${att.file_name}</span>
                `;

                attDiv.addEventListener('click', () => {
                    alert('ממשק זה אינו כולל את הקבצים המצורפים');
                });

                bubbleDiv.appendChild(attDiv);
            });
        }

        // 4. Text Content
        if (message.text) {
            const textDiv = document.createElement('div');
            textDiv.classList.add('markdown-content');
            textDiv.innerHTML = parseMarkdown(message.text);
            applyPerBlockDirection(textDiv);
            bubbleDiv.appendChild(textDiv);
        }

        // 5. Timestamp
        if (message.datetime) {
            const timeDiv = document.createElement('div');
            timeDiv.classList.add('timestamp');
            try {
                const date = new Date(message.datetime);
                const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                timeDiv.innerText = timeStr;
            } catch (e) {
                timeDiv.innerText = '';
            }
            bubbleDiv.appendChild(timeDiv);
        }

        containerDiv.appendChild(bubbleDiv);

        // 6. Branch Navigator
        if (isUser && message._nodeId) {
            const branchInfo = getBranchInfo(message._nodeId);
            if (branchInfo) {
                const navDiv = createBranchNavigator(branchInfo);
                containerDiv.appendChild(navDiv);
            }
        }

        rowDiv.appendChild(containerDiv);
        return rowDiv;
    }

    /**
     * Apply per-block direction (mirrors Android MarkdownBlockRenderers)
     */
    function applyPerBlockDirection(container) {
        const blockSelectors = 'p, h1, h2, h3, h4, h5, h6, li, td, th, blockquote';
        const blocks = container.querySelectorAll(blockSelectors);

        if (blocks.length === 0) {
            applyDirToElement(container);
            return;
        }

        blocks.forEach(block => applyDirToElement(block));
    }

    function applyDirToElement(el) {
        if (currentDirectionMode === 'ltr') {
            el.style.direction = 'ltr';
            el.style.textAlign = 'left';
        } else if (currentDirectionMode === 'rtl') {
            el.style.direction = 'rtl';
            el.style.textAlign = 'right';
        } else {
            const dir = inferTextDirection(el.textContent);
            el.style.direction = dir;
            el.style.textAlign = dir === 'rtl' ? 'right' : 'left';
        }
    }

    function createBranchNavigator(branchInfo) {
        const navDiv = document.createElement('div');
        navDiv.classList.add('branch-navigator');

        const prevBtn = document.createElement('button');
        prevBtn.classList.add('branch-arrow');
        prevBtn.innerHTML = '‹';
        prevBtn.disabled = !branchInfo.hasPrevious;
        if (branchInfo.hasPrevious) {
            prevBtn.addEventListener('click', () => {
                switchVariant(branchInfo.nodeId, branchInfo.currentVariantIndex - 1);
            });
        }

        const counterSpan = document.createElement('span');
        counterSpan.classList.add('branch-counter');
        counterSpan.innerText = branchInfo.displayText;

        const nextBtn = document.createElement('button');
        nextBtn.classList.add('branch-arrow');
        nextBtn.innerHTML = '›';
        nextBtn.disabled = !branchInfo.hasNext;
        if (branchInfo.hasNext) {
            nextBtn.addEventListener('click', () => {
                switchVariant(branchInfo.nodeId, branchInfo.currentVariantIndex + 1);
            });
        }

        navDiv.appendChild(prevBtn);
        navDiv.appendChild(counterSpan);
        navDiv.appendChild(nextBtn);

        return navDiv;
    }

    // ============================
    // Text Direction Controls
    // ============================

    function setDirection(mode) {
        currentDirectionMode = mode;
        updateDirectionButtons();
        // Re-render but PRESERVE scroll position
        renderChat(true);
    }

    function updateDirectionButtons() {
        // Remove active from all
        dirRtlBtn.classList.remove('active');
        dirAutoBtn.classList.remove('active');
        dirLtrBtn.classList.remove('active');

        // Add active to current
        if (currentDirectionMode === 'rtl') dirRtlBtn.classList.add('active');
        else if (currentDirectionMode === 'auto') dirAutoBtn.classList.add('active');
        else dirLtrBtn.classList.add('active');
    }

    // ============================
    // Markdown + LaTeX
    // ============================

    /**
     * Parse markdown text with LaTeX support.
     * Mirrors Android's LatexRenderer.parseLatexSegments():
     * 1. Extract $$...$$ (display) and $...$ (inline) LaTeX before markdown parsing
     * 2. Replace with placeholder tokens
     * 3. Parse markdown
     * 4. Replace tokens with KaTeX-rendered HTML
     */
    function parseMarkdown(text) {
        // Step 1: Extract and protect LaTeX from markdown parser
        const latexMap = {};
        let counter = 0;

        // Replace display LaTeX ($$...$$) first (greedy — match the pair of $$)
        let processed = text.replace(/\$\$([\s\S]+?)\$\$/g, (match, latex) => {
            const id = `%%DISPLAY_LATEX_${counter++}%%`;
            latexMap[id] = { latex: latex.trim(), display: true };
            return id;
        });

        // Replace inline LaTeX ($...$) — single line only, non-empty
        processed = processed.replace(/\$([^\$\n]+?)\$/g, (match, latex) => {
            if (!latex.trim()) return match;
            const id = `%%INLINE_LATEX_${counter++}%%`;
            latexMap[id] = { latex: latex, display: false };
            return id;
        });

        // Step 2: Parse markdown
        let html;
        if (window.marked && typeof window.marked.parse === 'function') {
            window.marked.setOptions({ gfm: true, breaks: false });
            html = window.marked.parse(processed);
        } else {
            html = processed.replace(/\n/g, '<br>');
        }

        // Step 3: Replace placeholders with rendered LaTeX
        for (const [id, info] of Object.entries(latexMap)) {
            const rendered = renderLatex(info.latex, info.display);
            html = html.replace(id, rendered);
        }

        return html;
    }

    /**
     * Render a LaTeX expression to HTML.
     * Uses KaTeX if available, falls back to Unicode conversion
     * matching Android's LatexRenderer.convertLatexToUnicode().
     */
    function renderLatex(latex, isDisplay) {
        // Try KaTeX first
        if (window.katex) {
            try {
                const html = window.katex.renderToString(latex, {
                    displayMode: isDisplay,
                    throwOnError: false,
                    trust: true,
                    strict: false
                });
                if (isDisplay) {
                    return `<div class="latex-display">${html}</div>`;
                }
                return `<span class="latex-inline">${html}</span>`;
            } catch (e) {
                // Fall through to fallback
            }
        }

        // Fallback: Unicode conversion matching LatexRenderer.convertLatexToUnicode()
        const text = convertLatexToUnicode(latex);
        if (isDisplay) {
            return `<div class="latex-display latex-fallback">${escapeHtml(text)}</div>`;
        }
        return `<span class="latex-inline latex-fallback">${escapeHtml(text)}</span>`;
    }

    /**
     * Convert LaTeX to Unicode — mirrors LatexRenderer.convertLatexToUnicode()
     */
    function convertLatexToUnicode(latex) {
        const symbolMap = {
            // Greek lowercase
            '\\alpha': 'α', '\\beta': 'β', '\\gamma': 'γ', '\\delta': 'δ',
            '\\epsilon': 'ε', '\\varepsilon': 'ε', '\\zeta': 'ζ', '\\eta': 'η',
            '\\theta': 'θ', '\\vartheta': 'ϑ',
            '\\iota': 'ι', '\\kappa': 'κ', '\\lambda': 'λ', '\\mu': 'μ',
            '\\nu': 'ν', '\\xi': 'ξ', '\\pi': 'π', '\\varpi': 'ϖ',
            '\\rho': 'ρ', '\\varrho': 'ϱ',
            '\\sigma': 'σ', '\\varsigma': 'ς', '\\tau': 'τ', '\\upsilon': 'υ',
            '\\phi': 'φ', '\\varphi': 'φ',
            '\\chi': 'χ', '\\psi': 'ψ', '\\omega': 'ω',
            // Greek uppercase
            '\\Gamma': 'Γ', '\\Delta': 'Δ', '\\Theta': 'Θ', '\\Lambda': 'Λ',
            '\\Xi': 'Ξ', '\\Pi': 'Π', '\\Sigma': 'Σ', '\\Phi': 'Φ',
            '\\Psi': 'Ψ', '\\Omega': 'Ω',
            // Operators and symbols
            '\\infty': '∞', '\\partial': '∂', '\\nabla': '∇',
            '\\pm': '±', '\\mp': '∓', '\\times': '×', '\\div': '÷',
            '\\leq': '≤', '\\le': '≤', '\\geq': '≥', '\\ge': '≥',
            '\\neq': '≠', '\\ne': '≠',
            '\\approx': '≈', '\\equiv': '≡', '\\sim': '∼', '\\simeq': '≃',
            '\\propto': '∝',
            '\\in': '∈', '\\notin': '∉', '\\subset': '⊂', '\\supset': '⊃',
            '\\subseteq': '⊆', '\\supseteq': '⊇',
            '\\cap': '∩', '\\cup': '∪',
            '\\int': '∫', '\\iint': '∬', '\\iiint': '∭', '\\oint': '∮',
            '\\sum': '∑', '\\prod': '∏', '\\sqrt': '√',
            '\\cdot': '·', '\\bullet': '•',
            '\\ldots': '…', '\\cdots': '⋯', '\\vdots': '⋮', '\\ddots': '⋱',
            '\\rightarrow': '→', '\\to': '→', '\\leftarrow': '←',
            '\\Rightarrow': '⇒', '\\Leftarrow': '⇐',
            '\\leftrightarrow': '↔', '\\Leftrightarrow': '⇔',
            '\\uparrow': '↑', '\\downarrow': '↓',
            '\\forall': '∀', '\\exists': '∃', '\\nexists': '∄',
            '\\emptyset': '∅', '\\varnothing': '∅',
            '\\angle': '∠', '\\perp': '⊥', '\\parallel': '∥',
            '\\oplus': '⊕', '\\otimes': '⊗',
            '\\lim': 'lim', '\\sin': 'sin', '\\cos': 'cos', '\\tan': 'tan',
            '\\ln': 'ln', '\\log': 'log', '\\exp': 'exp',
            '\\min': 'min', '\\max': 'max', '\\sup': 'sup', '\\inf': 'inf',
            '\\det': 'det', '\\dim': 'dim', '\\deg': 'deg', '\\arg': 'arg'
        };

        let result = latex;
        // Sort by key length descending to avoid partial replacements
        const sortedKeys = Object.keys(symbolMap).sort((a, b) => b.length - a.length);
        for (const key of sortedKeys) {
            result = result.split(key).join(symbolMap[key]);
        }
        return result;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
});
