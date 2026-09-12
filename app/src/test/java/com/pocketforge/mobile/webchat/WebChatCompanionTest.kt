package com.pocketforge.mobile.webchat

import com.pocketforge.mobile.model.Project
import com.pocketforge.mobile.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChatCompanionTest {

    private val sampleProject = Project(
        id = "test-proj-1",
        name = "Pocket Counter",
        description = "A simple counter app",
        language = "TypeScript",
        slug = "pocket-counter",
        rootPath = "/tmp/test-proj-1",
        updatedAtMillis = 1000L,
    )

    private val sampleFiles = listOf(
        WorkspaceEntry(
            path = "src/App.tsx",
            name = "App.tsx",
            isDirectory = false,
            depth = 1,
            sizeBytes = 250L,
        ),
        WorkspaceEntry(
            path = "package.json",
            name = "package.json",
            isDirectory = false,
            depth = 0,
            sizeBytes = 180L,
        ),
    )

    @Test
    fun testWebChatProvidersHaveValidUrls() {
        WebChatProvider.entries.forEach { provider ->
            assertTrue("Display name must not be blank for $provider", provider.displayName.isNotBlank())
            assertTrue("Description must not be blank for $provider", provider.description.isNotBlank())
            if (provider != WebChatProvider.CUSTOM) {
                assertTrue("Provider URL must start with https:// for $provider", provider.url.startsWith("https://"))
            }
        }
    }

    @Test
    fun testPromptBuilderSmartContextMode() {
        val result = WebChatPromptBuilder.buildPrompt(
            userQuery = "Add a reset button to the counter",
            mode = PromptContextMode.SMART_CONTEXT,
            project = sampleProject,
            workspaceFiles = sampleFiles,
        )

        assertTrue(result.prompt.contains("Add a reset button to the counter"))
        assertTrue(result.prompt.contains("Pocket Counter"))
        assertTrue(result.prompt.contains("Auto-Import Convention"))
        assertFalse(result.prompt.contains("Recent Terminal Output"))
    }

    @Test
    fun testPromptBuilderActiveFileMode() {
        val activeCode = "export function App() { return <div>Count: 0</div>; }"
        val result = WebChatPromptBuilder.buildPrompt(
            userQuery = "Add a reset button",
            mode = PromptContextMode.ACTIVE_FILE,
            project = sampleProject,
            workspaceFiles = sampleFiles,
            activeFilePath = "src/App.tsx",
            activeFileContent = activeCode,
        )

        assertTrue(result.prompt.contains("## File: src/App.tsx"))
        assertTrue(result.prompt.contains(activeCode))
        assertTrue(result.prompt.contains("Auto-Import Convention"))
        assertTrue(result.estimatedTokens > 0)
    }

    @Test
    fun testPromptBuilderDebugFixMode() {
        val errorText = "TS2304: Cannot find name 'handleReset'"
        val result = WebChatPromptBuilder.buildPrompt(
            userQuery = "Fix this compile error",
            mode = PromptContextMode.DEBUG_FIX,
            project = sampleProject,
            workspaceFiles = sampleFiles,
            activeFilePath = "src/App.tsx",
            activeFileContent = "export function App() { return <button onClick={handleReset}>Reset</button>; }",
            terminalErrors = errorText,
        )

        assertTrue(result.prompt.contains("Recent Terminal Output / Errors"))
        assertTrue(result.prompt.contains(errorText))
    }

    @Test
    fun testPromptBuilderRawPromptMode() {
        val raw = "Explain how useEffect works"
        val result = WebChatPromptBuilder.buildPrompt(
            userQuery = raw,
            mode = PromptContextMode.RAW_PROMPT,
            project = sampleProject,
            workspaceFiles = sampleFiles,
        )

        assertEquals(raw, result.prompt.trim())
    }

    @Test
    fun testResponseParserSingleFileCodeBlockWithFilename() {
        val response = """
            Here is the updated App.tsx component:

            ```tsx:src/App.tsx
            import React from 'react';

            export function App() {
                return <button>Click</button>;
            }
            ```

            Let me know if you need anything else!
        """.trimIndent()

        val parsed = WebChatResponseParser.parse(response)
        assertEquals(1, parsed.detectedFiles.size)
        val file = parsed.detectedFiles.first()
        assertEquals("src/App.tsx", file.relativePath)
        assertTrue(file.content.contains("import React"))
        assertTrue(file.content.contains("export function App"))
        assertTrue(parsed.explanationText.contains("Here is the updated App.tsx component"))
    }

    @Test
    fun testResponseParserMultiFileMarkdown() {
        val response = """
            I updated two files for your project:

            ### File: `src/components/Counter.tsx`
            ```tsx
            export const Counter = () => <div>0</div>;
            ```

            ### File: `src/styles/counter.css`
            ```css
            .counter { color: blue; }
            ```

            Both files are ready.
        """.trimIndent()

        val parsed = WebChatResponseParser.parse(response)
        assertEquals(2, parsed.detectedFiles.size)
        val paths = parsed.detectedFiles.map { it.relativePath }
        assertTrue(paths.contains("src/components/Counter.tsx"))
        assertTrue(paths.contains("src/styles/counter.css"))
        assertEquals("export const Counter = () => <div>0</div>;", parsed.detectedFiles[0].content.trim())
        assertEquals(".counter { color: blue; }", parsed.detectedFiles[1].content.trim())
    }

    @Test
    fun testResponseParserWithCommentInCodeBlock() {
        val response = """
            ```typescript
            // file: src/utils/math.ts
            export function add(a: number, b: number): number {
                return a + b;
            }
            ```
        """.trimIndent()

        val parsed = WebChatResponseParser.parse(response)
        assertEquals(1, parsed.detectedFiles.size)
        assertEquals("src/utils/math.ts", parsed.detectedFiles.first().relativePath)
        assertTrue(parsed.detectedFiles.first().content.contains("export function add"))
    }

    @Test
    fun testResponseParserSanitizesPathTraversal() {
        val malicious = """
            ```typescript:../../etc/passwd
            root:x:0:0:root:/root:/bin/bash
            ```
        """.trimIndent()

        val parsed = WebChatResponseParser.parse(malicious)
        if (parsed.detectedFiles.isNotEmpty()) {
            assertFalse(
                "Parsed path must not start with ..",
                parsed.detectedFiles.first().relativePath.startsWith("..")
            )
        }
    }
}
