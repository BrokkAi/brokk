import { useCallback, useState } from "react";

type Props = {
  onSubmit: (prompt: string) => void | Promise<void>;
  disabled?: boolean;
};

export function PromptPanel({ onSubmit, disabled }: Props) {
  const [prompt, setPrompt] = useState<string>("");

  const submit = useCallback(async () => {
    const text = prompt.trim();
    if (!text) return;
    await onSubmit(text);
    setPrompt("");
  }, [prompt, onSubmit]);

  return (
    <div className="stack">
      <div className="title">Prompt</div>
      <textarea
        className="textarea"
        placeholder="Ask the model something about your repo..."
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        disabled={disabled}
      />
      <div className="hstack">
        <button className="btn" onClick={submit} disabled={disabled || !prompt.trim()}>
          Ask
        </button>
      </div>
    </div>
  );
}
