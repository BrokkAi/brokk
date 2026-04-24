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

---

## Audit -- problemes identifies (2026-04-24)

Relecture complete du serveur ACP Rust. Problemes classes par severite.

### Bugs critiques

1. **Streaming casse** (`tool_loop.rs:46-68`). Le callback `on_token` bufferise les tokens dans un `Vec<String>` et ne les flush qu'apres que `stream_chat` ait termine. Le client ACP ne voit donc rien jusqu'a la fin de la reponse LLM. Correction : passer `on_text` directement via un `Arc<Mutex<dyn FnMut>>` partage entre les tours.

2. **Mode et modele de session non persistes** (`session.rs`). `get_session` recharge toujours avec `mode: SessionMode::Lutz` et `model: default_model`, les changements via `set_mode` ne sont jamais ecrits dans le zip. Correction : ajouter `mode` et `model` au `SessionManifest`.

3. **Replay d'historique incomplet** (`agent.rs:156-158`). Seul `turn.agent_response` est renvoye au client, les prompts utilisateur sont omis. Correction : envoyer aussi `turn.user_prompt` (role user).

4. **Tool calls non persistes dans l'historique**. `ConversationTurn` ne stocke que `user_prompt` + `agent_response`. Au rechargement, le LLM ne voit plus ses tool calls/results intermediaires. Scope plus large -- voir "ameliorations differees".

5. **Arguments de tool malformes silencieusement avales** (`tool_loop.rs:92`). `serde_json::from_str(...).unwrap_or_default()` appelle le tool avec `{}` sans signaler l'erreur au LLM. Correction : renvoyer un `tool_result` d'erreur de parsing.

### Securite

6. **Trou dans `safe_resolve_for_write`** (`tools/mod.rs:236-255`). Si le parent du chemin demande n'existe pas, le check `starts_with(cwd)` est totalement saute et le `joined` non canonicalise est retourne. Path traversal possible via `../nouveau_dossier/../../tmp/evil`. Correction : remonter vers le premier ancetre existant, canonicaliser celui-ci, et valider la prefixe.

7. **`runShellCommand` sans sandbox**. `sh -c` execute n'importe quoi (cat /etc/passwd, curl | sh, etc.). Aucun seccomp, namespace ou allow-list. Pour un outil pilote par LLM c'est un risque majeur. Correction : hors scope immediat, mais documenter et envisager une confirmation client ACP.

8. **Symlinks suivis aveuglement**. Un symlink sous cwd pointant dehors laisse passer les ecritures. Mitige par canonicalize dans `safe_resolve` pour la lecture ; le write reste expose.

### Concurrence / performance

9. **I/O bloquantes sous le runtime tokio** (`session.rs`, `tools/filesystem.rs`). Tous les appels `std::fs` et zip se font en `sync` depuis des methodes `async`. Correction : envelopper dans `tokio::task::spawn_blocking`.

10. **`add_turn` bloque tout le `SessionStore`** (`session.rs:605-618`). Le `RwLock::write()` sur `sessions` est tenu pendant toute la compression zip. Correction : cloner les donnees necessaires hors du lock avant `spawn_blocking`.

11. **`list_models()` refait a chaque `session/new`** (`agent.rs:106`). Un appel HTTP par creation de session pour une liste deja recuperee a l'init. Correction : cacher la liste dans `SessionStore`.

12. **`search_file_contents` sans filtre de taille ni detection de binaires** (`filesystem.rs:161`). Tente de lire tout fichier non-filtre par le glob. Correction : skip si > 1 MiB ou si les premiers octets contiennent un NUL.

13. **Pas d'eviction des sessions en memoire**. `SessionStore::sessions` grandit indefiniment. Hors scope immediat (YAGNI).

### Fonctionnalites manquantes

14. **Bifrost/brokk_analyzer jamais integre** (`tools/mod.rs:21`). Le `headline()` reference `search_symbols`, `get_symbol_locations` etc. mais aucun n'est enregistre. Phase 2 du plan non realisee -- scope separe.

15. **`max_turns` hardcode a 25** (`agent.rs:271`). Correction : exposer `--max-turns`.

16. **Pas de fallback pour modeles sans tool calling**. Beaucoup de petits Ollama cassent. Hors scope immediat.

17. **Mode invalide accepte silencieusement** (`agent.rs:315-317`). `SessionMode::parse` None ignore et `SetSessionModeResponse::new()` repond succes. Correction : retourner une erreur JSON-RPC ou un `meta` d'erreur.

18. **`SetSessionModeResponse` succes meme si la session n'existe pas**. Correction : valider l'existence avant de repondre.

### Qualite de code

19. **Tool calls tronques renvoyes sur cancel** (`llm_client.rs:430-437`). Si le stream est coupe au milieu des arguments, `tool_acc.is_empty()` est faux et les calls partiels remontent. Correction : si cancel.is_cancelled(), renvoyer seulement le texte.

20. **Logs qui avalent les erreurs d'ecriture zip** (`session.rs`). `append_turn_to_zip` log `warn` et continue, la memoire croit avoir persiste alors que le disque est desynchronise. Correction (partielle) : propager l'erreur via `Result`.

21. **Pas de tests**. Aucun `#[test]` ni `tests/`. Ecart vs cote Java. Hors scope immediat.

22. **`ChatMessage.role` non type**. `String` partout. Hors scope immediat (YAGNI).

---

## Plan de remediation (ordre d'attaque)

1. Streaming (bug critique)
2. Path-traversal `safe_resolve_for_write`
3. Persistance mode/modele
4. Replay utilisateur
5. Arguments malformes -> erreur
6. I/O bloquantes -> spawn_blocking
7. `max_turns` configurable
8. Filtre binaires/taille dans `search_file_contents`
9. Cache des modeles
10. Validation set_mode
11. Tool calls tronques sur cancel
