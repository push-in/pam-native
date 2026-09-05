"use strict";

const vscode = require("vscode");
const cp = require("child_process");
const fs = require("fs");
const path = require("path");

class PamLanguageClient {
    constructor(cwd, executable, diagnostics, args = []) {
        this.nextId = 1;
        this.pending = new Map();
        this.buffer = Buffer.alloc(0);
        this.diagnostics = diagnostics;
        this.process = cp.spawn(executable, args, {cwd, stdio: ["pipe", "pipe", "pipe"]});
        this.process.stdout.on("data", chunk => this.consume(chunk));
        this.process.stderr.setEncoding("utf8").on("data", message => {
            if (message.trim()) console.error(`[pam-native] ${message.trim()}`);
        });
        this.process.on("exit", code => {
            for (const {reject} of this.pending.values()) reject(new Error(`PAM language server exited with ${code}`));
            this.pending.clear();
        });
        this.process.on("error", error => {
            for (const {reject} of this.pending.values()) reject(error);
            this.pending.clear();
            vscode.window.showErrorMessage(`PAM Native language server could not start: ${error.message}. Run pam doctor --fix.`);
        });
    }

    send(payload) {
        const json = JSON.stringify({jsonrpc: "2.0", ...payload});
        this.process.stdin.write(`Content-Length: ${Buffer.byteLength(json)}\r\n\r\n${json}`);
    }

    request(method, params) {
        const id = this.nextId++;
        this.send({id, method, params});
        return new Promise((resolve, reject) => this.pending.set(id, {resolve, reject}));
    }

    notify(method, params) { this.send({method, params}); }

    consume(chunk) {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        while (true) {
            const headerEnd = this.buffer.indexOf("\r\n\r\n");
            if (headerEnd < 0) return;
            const header = this.buffer.subarray(0, headerEnd).toString("ascii");
            const match = /Content-Length:\s*(\d+)/i.exec(header);
            if (!match) { this.buffer = Buffer.alloc(0); return; }
            const length = Number(match[1]);
            const bodyStart = headerEnd + 4;
            if (this.buffer.length < bodyStart + length) return;
            const body = this.buffer.subarray(bodyStart, bodyStart + length).toString("utf8");
            this.buffer = this.buffer.subarray(bodyStart + length);
            this.message(JSON.parse(body));
        }
    }

    message(message) {
        if (message.id !== undefined) {
            const pending = this.pending.get(message.id);
            if (!pending) return;
            this.pending.delete(message.id);
            if (message.error) pending.reject(new Error(message.error.message));
            else pending.resolve(message.result);
            return;
        }
        if (message.method === "textDocument/publishDiagnostics") {
            const uri = vscode.Uri.parse(message.params.uri);
            const values = message.params.diagnostics.map(diagnostic => {
                const start = new vscode.Position(diagnostic.range.start.line, diagnostic.range.start.character);
                const end = new vscode.Position(diagnostic.range.end.line, diagnostic.range.end.character);
                const item = new vscode.Diagnostic(new vscode.Range(start, end), diagnostic.message, diagnostic.severity - 1);
                item.source = diagnostic.source;
                return item;
            });
            this.diagnostics.set(uri, values);
        }
    }

    open(document) {
        this.notify("textDocument/didOpen", {textDocument: {uri: document.uri.toString(), languageId: "pam", version: document.version, text: document.getText()}});
    }

    change(document) {
        this.notify("textDocument/didChange", {textDocument: {uri: document.uri.toString(), version: document.version}, contentChanges: [{text: document.getText()}]});
    }

    close(document) { this.notify("textDocument/didClose", {textDocument: {uri: document.uri.toString()}}); }
}

function isPam(document) {
    return document.languageId === "pam" || /\.pam(?:\.php)?$/.test(document.uri.path);
}

function isPhpPosition(document, position) {
    const prefix = document.getText(new vscode.Range(new vscode.Position(0, 0), position));
    return prefix.lastIndexOf("<?php") > prefix.lastIndexOf("?>");
}

const pamSelector = [
    {language: "pam"},
    {language: "php", pattern: "**/*.pam"},
    {language: "php", pattern: "**/*.pam.php"},
];

async function activate(context) {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    const activeDocument = vscode.window.activeTextEditor?.document;
    const cwd = workspace?.uri.fsPath
        ?? (activeDocument?.uri.scheme === "file" ? path.dirname(activeDocument.uri.fsPath) : process.cwd());
    const configured = vscode.workspace.getConfiguration("pam").get("languageServer.path", "").trim();
    const projectExecutable = process.platform === "win32"
        ? path.join(cwd, "vendor", "bin", "pam-native-language-server.bat")
        : path.join(cwd, "vendor", "bin", "pam-native-language-server");
    if (!configured && !fs.existsSync(projectExecutable) && !vscode.workspace.textDocuments.some(isPam)) return;
    const executable = configured || (fs.existsSync(projectExecutable) ? projectExecutable : "pam-native-language-server");
    const diagnostics = vscode.languages.createDiagnosticCollection("pam-native");
    const runtime = vscode.workspace.getConfiguration("pam").get("languageServer.runtime", "pam");
    const client = configured
        ? new PamLanguageClient(cwd, executable, diagnostics)
        : new PamLanguageClient(cwd, runtime, diagnostics, ["exec", executable]);
    await client.request("initialize", {processId: process.pid, rootUri: workspace?.uri.toString() ?? null, capabilities: {}});
    client.notify("initialized", {});

    for (const document of vscode.workspace.textDocuments.filter(document => isPam(document))) client.open(document);
    context.subscriptions.push(
        diagnostics,
        vscode.workspace.onDidOpenTextDocument(document => { if (isPam(document)) client.open(document); }),
        vscode.workspace.onDidChangeTextDocument(event => { if (isPam(event.document)) client.change(event.document); }),
        vscode.workspace.onDidCloseTextDocument(document => { if (isPam(document)) client.close(document); }),
        vscode.languages.registerDocumentFormattingEditProvider(pamSelector, {
            async provideDocumentFormattingEdits(document, options) {
                const edits = await client.request("textDocument/formatting", {textDocument: {uri: document.uri.toString()}, options});
                return edits.map(edit => vscode.TextEdit.replace(
                    new vscode.Range(edit.range.start.line, edit.range.start.character, edit.range.end.line, edit.range.end.character),
                    edit.newText,
                ));
            },
        }),
        vscode.languages.registerCompletionItemProvider(pamSelector, {
            async provideCompletionItems(document, position) {
                if (document.languageId === "php" && isPhpPosition(document, position)) return null;
                return client.request("textDocument/completion", {textDocument: {uri: document.uri.toString()}, position});
            },
        }, "<", ":", "@"),
        vscode.languages.registerHoverProvider(pamSelector, {
            async provideHover(document, position) {
                if (document.languageId === "php" && isPhpPosition(document, position)) return null;
                const hover = await client.request("textDocument/hover", {textDocument: {uri: document.uri.toString()}, position});
                return hover ? new vscode.Hover(new vscode.MarkdownString(hover.contents.value)) : null;
            },
        }),
        vscode.languages.registerDefinitionProvider(pamSelector, {
            async provideDefinition(document, position) {
                if (document.languageId === "php" && isPhpPosition(document, position)) return null;
                const definition = await client.request("textDocument/definition", {textDocument: {uri: document.uri.toString()}, position});
                if (!definition) return null;
                return new vscode.Location(
                    vscode.Uri.parse(definition.uri),
                    new vscode.Range(definition.range.start.line, definition.range.start.character, definition.range.end.line, definition.range.end.character),
                );
            },
        }),
        {dispose() { client.request("shutdown", null).finally(() => client.notify("exit", null)); }},
    );
}

function deactivate() {}

module.exports = {activate, deactivate};
