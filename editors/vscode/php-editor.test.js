const vscode = require('vscode');
const fs = require('fs');
const assert = require('assert');
exports.run = async () => {
 const doc = await vscode.workspace.openTextDocument(vscode.Uri.joinPath(vscode.workspace.workspaceFolders[0].uri, 'src/Components/PrimaryButton.pam'));
 await vscode.window.showTextDocument(doc);
 assert.equal(doc.languageId, 'php');
 const lines = doc.getText().split('\n');
 const line = lines.findIndex(x => x.includes('extends Component'));
 const pos = new vscode.Position(line, lines[line].lastIndexOf('Component')+2);
 let defs=[];
 for(let i=0;i<45;i++) {
  defs=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',doc.uri,pos)||[];
  if(defs.some(x=>(x.uri||x.targetUri).path.endsWith('/Component.php')))break;
  await new Promise(r=>setTimeout(r,1000));
 }
 assert(defs.some(x=>(x.uri||x.targetUri).path.endsWith('/Component.php')), 'Ctrl+click Component must resolve SDK PHP');
 const memberLine=lines.findIndex(x=>x.includes("$this->emit"));
 const memberPos=new vscode.Position(memberLine,lines[memberLine].indexOf('emit')+1);
 const members=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',doc.uri,memberPos)||[];
 assert(members.length>0,'Inherited PHP method definition must resolve');
 const hover=await vscode.commands.executeCommand('vscode.executeHoverProvider',doc.uri,memberPos);
 assert(hover?.length,'PHP hover must resolve');
 console.log(JSON.stringify({phpLanguage:true,classDefinition:defs.length,methodDefinition:members.length,hover:hover.length}));
};
