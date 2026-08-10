"use strict";

const assert = require("assert");
const cp = require("child_process");
const path = require("path");
const fs = require("fs");
const os = require("os");

const server = cp.spawn(
    "php",
    [path.resolve(__dirname, "../../packages/native/bin/pam-native-language-server")],
    {stdio: ["pipe", "pipe", "inherit"]},
);

let nextId = 1;
let buffer = Buffer.alloc(0);
const pending = new Map();

server.stdout.on("data", chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
        const end = buffer.indexOf("\r\n\r\n");
        if (end < 0) return;
        const match = /Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, end).toString("ascii"));
        assert(match);
        const length = Number(match[1]);
        if (buffer.length < end + 4 + length) return;
        const message = JSON.parse(buffer.subarray(end + 4, end + 4 + length).toString("utf8"));
        buffer = buffer.subarray(end + 4 + length);
        if (message.id !== undefined && pending.has(message.id)) {
            pending.get(message.id)(message.result);
            pending.delete(message.id);
        }
    }
});

function send(payload) {
    const json = JSON.stringify({jsonrpc: "2.0", ...payload});
    server.stdin.write(`Content-Length: ${Buffer.byteLength(json)}\r\n\r\n${json}`);
}

function request(method, params) {
    const id = nextId++;
    send({id, method, params});
    return new Promise(resolve => pending.set(id, resolve));
}

(async () => {
    const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "pam-lsp-"));
    const component = path.join(workspace, "ProfileCard.pam");
    fs.writeFileSync(component, "<?php final class ProfileCard {} ?>\n<template><Text>Profile</Text></template>\n");
    const rootUri = `file://${workspace}`;
    const initialized = await request("initialize", {capabilities: {}, rootUri});
    assert.strictEqual(initialized.capabilities.documentFormattingProvider, true);
    assert.strictEqual(initialized.capabilities.definitionProvider, true);
    assert.deepStrictEqual(initialized.capabilities.completionProvider.triggerCharacters, ["<", ":", "@"]);
    const uri = "file:///Demo.pam";
    send({method: "textDocument/didOpen", params: {textDocument: {uri, languageId: "pam", version: 1, text: "<?php public string $title = ''; ?>\n<template><Text>{{ $title }}</Text></template>"}}});
    const completions = await request("textDocument/completion", {textDocument: {uri}, position: {line: 1, character: 10}});
    assert(completions.some(item => item.label === "$title"));
    assert(completions.some(item => item.label === "p-if"));
    assert(completions.some(item => item.label === "ProfileCard"));
    const usage = "<?php final class Demo {} ?>\n<template><ProfileCard /></template>";
    send({method: "textDocument/didChange", params: {textDocument: {uri, version: 2}, contentChanges: [{text: usage}]}});
    const definition = await request("textDocument/definition", {textDocument: {uri}, position: {line: 1, character: 13}});
    assert(definition.uri.endsWith("ProfileCard.pam"));
    await request("shutdown", null);
    send({method: "exit"});
    server.stdin.end();
    fs.rmSync(workspace, {recursive: true, force: true});
})().catch(error => {
    console.error(error);
    server.kill();
    process.exitCode = 1;
});
