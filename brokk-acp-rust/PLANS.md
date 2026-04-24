# Plan: Tool Calling pour le serveur ACP Rust

## Etat actuel

Le serveur Rust est un simple proxy chat : il recoit un prompt, l'envoie au LLM, et stream la reponse. Pas de boucle agentique, pas de tool calling, pas d'interaction avec le filesystem.

## Atout majeur : Bifrost (brokk_analyzer)

Le crate `brokk_analyzer` (repo `brokkai/bifrost`) est un analyseur de code Rust natif base sur tree-sitter. Il fournit deja un `SearchToolsService` avec ces tools prets a l'emploi :

| Tool Bifrost | Description |
|---|---|
| `search_symbols` | Chercher des symboles indexes dans le workspace |
| `get_symbol_locations` | Localiser des symboles dans les fichiers |
| `get_symbol_summaries` | Resumes ranges des symboles |
| `get_symbol_sources` | Code source des symboles |
| `get_file_summaries` | Resumes ranges des fichiers |
| `summarize_symbols` | Resumes recursifs compacts |
| `skim_files` | Apercu rapide des symboles d'un fichier |
| `most_relevant_files` | Fichiers lies par historique git et imports |
| `refresh` | Rafraichir l'index de l'analyseur |

Bifrost supporte : Java, JavaScript, TypeScript, Rust, Go, Python, C++, C#, PHP, Scala.

**L'integration est directe** : ajouter `brokk_analyzer` comme dependance, instancier `SearchToolsService::new(cwd)`, et appeler `service.call_tool_value(name, args)`. Pas de subprocess, pas de MCP, pas de serialisation -- c'est un appel de fonction Rust natif.

## Objectif

Permettre au LLM d'appeler des outils via le protocole OpenAI tool calling, avec une boucle agentique qui itere jusqu'a ce que le LLM donne une reponse finale. Les tools de code intelligence viennent de Bifrost ; les tools filesystem/shell sont implementes dans le serveur ACP.

---

## Phase 1 : Infrastructure du tool calling dans le client LLM

### 1.1 Etendre ChatMessage et ChatCompletionRequest (llm_client.rs)

```rust
#[derive(Serialize, Deserialize)]
struct ChatMessage {
    role: String,                              // "system", "user", "assistant", "tool"
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_calls: Option<Vec<ToolCall>>,         // reponses assistant avec tool calls
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_call_id: Option<String>,              // pour les messages role=tool
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,                      // nom du tool pour role=tool
}

#[derive(Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<ToolDefinition>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<String>,               // "auto" | "none" | "required"
}

#[derive(Serialize, Deserialize, Clone)]
struct ToolDefinition {
    r#type: String,          // "function"
    function: FunctionDef,
}

#[derive(Serialize, Deserialize, Clone)]
struct FunctionDef {
    name: String,
    description: String,
    parameters: serde_json::Value,
}

#[derive(Serialize, Deserialize, Clone)]
struct ToolCall {
    id: String,
    r#type: String,          // "function"
    function: FunctionCall,
}

#[derive(Serialize, Deserialize, Clone)]
struct FunctionCall {
    name: String,
    arguments: String,       // JSON serialise en string
}
```

### 1.2 Parser les tool calls dans le streaming SSE

Les chunks SSE peuvent contenir des `tool_calls` en fragments :

```json
{"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_abc", "function": {"name": "search_symbols", "arguments": ""}}]}}]}
{"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"patterns"}}]}}]}
{"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "\": [\"main\"]}"}}]}}]}
```

Il faut accumuler les fragments par index. Le `stream_chat` doit retourner :

```rust
enum LlmResponse {
    Text(String),
    ToolCalls {
        text: String,                           // texte emis avant les tool calls
        calls: Vec<ToolCall>,
    },
}
```

Fichier a modifier : `llm_client.rs`

---

## Phase 2 : ToolRegistry et integration Bifrost

### 2.1 ToolRegistry unifie (tools/mod.rs)

```rust
struct ToolRegistry {
    /// Tools Bifrost (code intelligence) -- appels directs au SearchToolsService
    bifrost: SearchToolsService,
    /// Repertoire de travail pour les tools filesystem
    cwd: PathBuf,
}

impl ToolRegistry {
    fn new(cwd: PathBuf) -> Result<Self, String>;

    /// Retourner toutes les definitions de tools au format OpenAI
    fn tool_definitions(&self) -> Vec<ToolDefinition>;

    /// Executer un tool par nom + arguments JSON
    fn execute(&mut self, name: &str, args: serde_json::Value) -> ToolResult;
}
```

### 2.2 Tools Bifrost (integration directe)

Pas besoin de les reimplementer. Le `SearchToolsService` de Bifrost a deja :
- `call_tool_value(name, arguments) -> Result<Value, Error>`

Le serveur ACP appelle `service.call_tool_value("search_symbols", args)` directement.

Les definitions de tools sont derivees de `list_tools_result()` dans `mcp_server.rs` de Bifrost. On peut les copier ou les generer depuis le service.

### 2.3 Tools supplementaires (filesystem + shell)

En plus des tools Bifrost, le serveur ACP a besoin de :

| Tool | Description | Implementation |
|---|---|---|
| `readFile` | Lire un fichier | `std::fs::read_to_string` avec validation de chemin |
| `writeFile` | Ecrire/creer un fichier | `std::fs::write` avec validation de chemin |
| `listDirectory` | Lister un repertoire | `std::fs::read_dir` |
| `runShellCommand` | Executer une commande shell | `tokio::process::Command` |
| `think` | Espace de reflexion (no-op) | Retourner le texte tel quel |

Ces tools sont simples et s'implementent en quelques dizaines de lignes chacun.

Fichiers a creer :
- `tools/mod.rs` (ToolRegistry, ToolResult, ToolDefinition)
- `tools/filesystem.rs` (readFile, writeFile, listDirectory)
- `tools/shell.rs` (runShellCommand)

---

## Phase 3 : La boucle agentique

### 3.1 Module tool_loop.rs

Reference : `LutzAgent.java` lignes 900-1100.

```
prompt utilisateur
    |
    v
[system prompt + history + user prompt]
    |
    v
[Envoyer au LLM avec tools=registry.tool_definitions()]
    |
    v
LlmResponse::Text? ----> Reponse finale, sortir
LlmResponse::ToolCalls?
    |
    v
Pour chaque tool_call:
  [Notifier le client ACP: "Searching for symbols..."]
  [registry.execute(name, args)]
  [Ajouter message role=tool avec le resultat]
    |
    v
[Renvoyer au LLM avec l'historique enrichi] ----> boucler
```

Protections :
- **Limite de tours** : max 25 iterations (configurable via `--max-turns`)
- **Annulation** : verifier `CancellationToken` avant chaque appel LLM et tool
- **Erreurs fatales** : arreter la boucle sur erreur interne critique
- **Taille des resultats** : tronquer les resultats de tools trop longs (>50KB)

### 3.2 Integration dans agent.rs

Remplacer `stream_chat` simple par `tool_loop::run(llm, registry, messages, cancel, on_event)`.

Le callback `on_event` envoie les notifications ACP au client :
- Tokens de texte -> `SessionUpdate::AgentMessageChunk`
- Debut de tool call -> message italique "_Searching for symbols..._"
- Resultat de tool -> pas affiche au client (interne au LLM)

---

## Phase 4 : Securite

### 4.1 Validation des chemins

Tous les tools filesystem (`readFile`, `writeFile`, `listDirectory`) doivent :
- Resoudre le chemin par rapport au cwd de la session
- Verifier que le chemin canonique reste sous le cwd (pas de `../../etc/passwd`)
- Refuser les chemins absolus sauf s'ils sont sous le cwd

```rust
fn safe_resolve(cwd: &Path, requested: &str) -> Result<PathBuf, String> {
    let resolved = cwd.join(requested).canonicalize()?;
    if !resolved.starts_with(cwd.canonicalize()?) {
        return Err("Path escapes the working directory".into());
    }
    Ok(resolved)
}
```

### 4.2 Sandboxing shell

`runShellCommand` :
- Timeout : 60 secondes par defaut
- cwd : toujours le cwd de la session
- stdin : ferme (pas de commandes interactives)
- Taille de sortie : tronquer stdout+stderr a 100KB
- Pas de shell interactif : executer via `sh -c "..."` ou `bash -c "..."`

---

## Phase 5 : Affichage ACP

Pendant la boucle agentique, envoyer des `SessionUpdate::AgentMessageChunk` :

```
_Searching for symbols..._
_Getting file summaries..._
_Running shell command..._
```

Correspondance des headlines (tiree de `ToolRegistry.java` et adaptee) :

```rust
fn headline(tool_name: &str) -> &str {
    match tool_name {
        "search_symbols" => "Searching for symbols",
        "get_symbol_locations" => "Finding files for symbols",
        "get_symbol_sources" => "Fetching symbol source",
        "get_file_summaries" => "Getting file summaries",
        "skim_files" => "Skimming files",
        "most_relevant_files" => "Finding related files",
        "readFile" => "Reading file",
        "writeFile" => "Writing file",
        "listDirectory" => "Listing directory",
        "runShellCommand" => "Running shell command",
        "think" => "Thinking",
        _ => tool_name,
    }
}
```

---

## Ordre d'implementation

| Phase | Travail | Estimation | Dependances |
|---|---|---|---|
| 1 | Etendre `llm_client.rs` : tools + tool_calls SSE | ~2 jours | - |
| 2 | ToolRegistry + integration Bifrost + tools filesystem/shell | ~2-3 jours | Bifrost crate |
| 3 | Boucle agentique `tool_loop.rs` + integration `agent.rs` | ~2 jours | Phases 1+2 |
| 4 | Securite (validation chemins, sandboxing shell) | ~1 jour | Phase 2 |
| 5 | Affichage ACP (headlines, notifications) | ~0.5 jour | Phase 3 |

**Total estime : ~7-8 jours.**

Sans Bifrost ce serait 12-15+ jours (reimplementation de tree-sitter, grammaires, index, etc.).

---

## Dependances a ajouter au Cargo.toml

```toml
# Bifrost -- analyseur de code Rust natif (code intelligence)
brokk_analyzer = { git = "https://github.com/brokkai/bifrost.git" }

# Deja present : tokio (avec feature "process" pour le shell)
# Deja present : serde_json, uuid, etc.
```

C'est tout. Bifrost apporte tree-sitter + grammaires + git2 + walkdir + glob comme dependances transitives.

---

## Questions ouvertes

1. **Version de Bifrost** : pointer vers `main` ou un tag precis ?

2. **Modeles sans tool calling** : certains modeles locaux (petits Ollama) ne supportent pas le tool calling. Faut-il un fallback text-based ? Ou simplement ne pas envoyer les tools et rester en mode chat simple ?

3. **Refresh automatique** : Bifrost a un `ProjectChangeWatcher` qui rafraichit l'index quand les fichiers changent. Faut-il l'activer ou laisser le LLM appeler `refresh` manuellement ?

4. **MCP tools du client ACP** : le protocole ACP permet au client de fournir des serveurs MCP. Faut-il s'y connecter pour avoir des tools supplementaires ? C'est un scope additionnel significatif.

5. **Persistance des tool calls** : les tool calls intermediaires doivent-ils etre persistes dans le zip de session pour le replay ? Le format actuel du zip supporte les `ChatMessageDto` avec role/contentId, donc c'est faisable.
