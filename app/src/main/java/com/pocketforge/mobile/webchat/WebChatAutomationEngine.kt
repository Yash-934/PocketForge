package com.pocketforge.mobile.webchat

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject

object WebChatAutomationEngine {

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.88 Mobile Safari/537.36"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(
        webView: WebView,
        isDesktopMode: Boolean = false,
        onPageFinishedExtra: (() -> Unit)? = null
    ) {
        webView.apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /**
     * Generates JavaScript code to automatically find the input field on Claude, ChatGPT,
     * DeepSeek, Gemini, etc., inject the prompt text, trigger React/Vue input events,
     * and automatically click the Send button.
     */
    fun buildAutoInjectAndSendScript(prompt: String): String {
        val safePromptJson = JSONObject.quote(prompt)
        return """
        (function() {
            try {
                var promptText = $safePromptJson;
                
                // Helper to trigger standard input and change events for React/Vue/ProseMirror
                function fireEvents(el) {
                    el.dispatchEvent(new Event('focus', { bubbles: true }));
                    el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    var ev = new InputEvent('input', { bubbles: true, inputType: 'insertText', data: promptText });
                    el.dispatchEvent(ev);
                }

                // 1. Find the target input element
                var inputEl = null;

                // Claude.ai (ProseMirror contenteditable div)
                var claudeInput = document.querySelector('div[contenteditable="true"].ProseMirror') 
                    || document.querySelector('div.ProseMirror')
                    || document.querySelector('[data-testid="chat-input"]');
                
                // ChatGPT (chatgpt.com)
                var gptInput = document.querySelector('#prompt-textarea') 
                    || document.querySelector('textarea[data-id="root"]')
                    || document.querySelector('div#prompt-textarea');

                // DeepSeek (chat.deepseek.com)
                var deepseekInput = document.querySelector('textarea#chat-input')
                    || document.querySelector('textarea[placeholder*="DeepSeek"]')
                    || document.querySelector('textarea[placeholder*="Send"]')
                    || document.querySelector('textarea');

                // Google Gemini (gemini.google.com)
                var geminiInput = document.querySelector('rich-textarea div[contenteditable="true"]')
                    || document.querySelector('.ql-editor')
                    || document.querySelector('div[contenteditable="true"]');

                // General fallback
                var genericInput = document.querySelector('textarea, div[contenteditable="true"], input[type="text"]');

                inputEl = claudeInput || gptInput || deepseekInput || geminiInput || genericInput;

                if (!inputEl) {
                    return JSON.stringify({ success: false, reason: "No chat input box found on page. Please log in first or wait for page to finish loading." });
                }

                // 2. Set text content
                inputEl.focus();
                
                if (inputEl.tagName.toLowerCase() === 'textarea' || inputEl.tagName.toLowerCase() === 'input') {
                    inputEl.value = promptText;
                    fireEvents(inputEl);
                } else if (inputEl.isContentEditable || inputEl.getAttribute('contenteditable') === 'true') {
                    // Contenteditable (Claude, Gemini, etc.)
                    // Try document.execCommand first for undo/state preservation in ProseMirror/Draft.js
                    try {
                        document.execCommand('selectAll', false, null);
                        document.execCommand('insertText', false, promptText);
                    } catch(e) {}
                    
                    if (!inputEl.innerText || inputEl.innerText.trim() !== promptText.trim()) {
                        inputEl.innerHTML = '<p>' + promptText.replace(/\n/g, '<br>') + '</p>';
                    }
                    fireEvents(inputEl);
                }

                // 3. Find and trigger Send button
                setTimeout(function() {
                    try {
                        var sendBtn = null;
                        
                        // Selectors for send buttons across providers
                        var btnSelectors = [
                            'button[aria-label="Send Message"]',
                            'button[aria-label="Send prompt"]',
                            'button[aria-label*="Send" i]',
                            'button[data-testid="send-button"]',
                            'button[type="submit"]',
                            'fieldset button',
                            'div[class*="send_button"]',
                            'div[class*="send-button"]',
                            'div[role="button"][aria-label*="Send" i]',
                            'button[class*="send" i]'
                        ];

                        for (var i = 0; i < btnSelectors.length; i++) {
                            var found = document.querySelector(btnSelectors[i]);
                            if (found && !found.disabled && found.getAttribute('aria-disabled') !== 'true') {
                                sendBtn = found;
                                break;
                            }
                        }

                        if (sendBtn) {
                            sendBtn.click();
                        } else {
                            // Fallback: Dispatch Enter key on the input
                            var enterEvent = new KeyboardEvent('keydown', {
                                key: 'Enter',
                                code: 'Enter',
                                keyCode: 13,
                                which: 13,
                                bubbles: true,
                                cancelable: true
                            });
                            inputEl.dispatchEvent(enterEvent);
                        }
                    } catch(err) {}
                }, 350);

                return JSON.stringify({ success: true, message: "Prompt injected and sent automatically!" });
            } catch(ex) {
                return JSON.stringify({ success: false, reason: ex.toString() });
            }
        })();
        """.trimIndent()
    }

    /**
     * Generates JavaScript code to extract the latest assistant response and code blocks
     * directly from the DOM of the active chat page.
     */
    fun buildExtractResponseScript(): String {
        return """
        (function() {
            try {
                var codeBlocks = [];
                var pres = document.querySelectorAll('pre');
                pres.forEach(function(pre) {
                    var code = pre.querySelector('code') || pre;
                    var text = code.innerText || code.textContent || '';
                    if (text.trim().length > 0) {
                        codeBlocks.push(text.trim());
                    }
                });

                // Extract assistant message container text
                var assistantSelectors = [
                    '.font-claude-message',
                    '[data-message-author-role="assistant"]',
                    '.ds-markdown',
                    '.model-response-text',
                    '.response-container',
                    'div[data-is-streaming="false"]',
                    'article[data-testid*="conversation-turn"]'
                ];

                var latestText = '';
                for (var i = 0; i < assistantSelectors.length; i++) {
                    var matches = document.querySelectorAll(assistantSelectors[i]);
                    if (matches.length > 0) {
                        var last = matches[matches.length - 1];
                        latestText = last.innerText || last.textContent || '';
                        if (latestText.trim().length > 30) {
                            break;
                        }
                    }
                }

                // If specific assistant container was not found, fallback to page innerText
                if (!latestText || latestText.trim().length < 20) {
                    latestText = document.body.innerText || '';
                }

                return JSON.stringify({
                    success: true,
                    fullText: latestText,
                    codeBlockCount: codeBlocks.length
                });
            } catch(e) {
                return JSON.stringify({ success: false, reason: e.toString() });
            }
        })();
        """.trimIndent()
    }
}
