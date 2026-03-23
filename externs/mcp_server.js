/**
 * @fileoverview Externs for Calva Backseat Driver MCP server
 * @externs
 */

// VS Code Extension properties
var vscode = {};
vscode.extensions = {};
vscode.extensions.getExtension = function() {};

var Extension = {};
Extension.packageJSON = {};
Extension.packageJSON.contributes = {};
Extension.packageJSON.contributes.languageModelTools = [];
Extension.packageJSON.contributes.chatSkills = [];
Extension.extensionUri = {};
Extension.extensionUri.fsPath = "";

// Tool properties
var Tool = {};
Tool.name = "";
Tool.modelDescription = "";
Tool.displayName = "";
Tool.userDescription = "";
Tool.inputSchema = {};
Tool.inputSchema.properties = {};

// Parameter properties
var Parameter = {};
Parameter.description = "";