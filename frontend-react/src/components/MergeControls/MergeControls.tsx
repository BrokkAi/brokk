type Props = {
  disabled?: boolean;
  onMerge: () => void | Promise<void>;
  onDiscard: () => void | Promise<void>;
};

export function MergeControls({ disabled, onMerge, onDiscard }: Props) {
  return (
    <div className="hstack">
      <button className="btn" onClick={onMerge} disabled={disabled}>
        Merge into main
      </button>
      <button className="btn" onClick={onDiscard} disabled={disabled}>
        Discard worktree
      </button>
    </div>
  );
}
