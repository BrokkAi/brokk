export interface EditBlockProperties {
    bubbleId: number;
    id: string;
    isExpanded: boolean;
    adds?: number;
    dels?: number;
    filename?: string;
    search?: string;
    replace?: string;
    headerOk: boolean;
    complete?: boolean; // set when block is structurally closed (tail/fence/etc)
    isGitDiff?: boolean;
}
