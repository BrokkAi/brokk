declare module "tar-stream" {
  import { Readable, Writable } from "node:stream";

  interface Header {
    name: string;
  }

  interface Extract extends Writable {
    on(event: "entry", listener: (header: Header, stream: Readable, next: () => void) => void): this;
    on(event: "finish", listener: () => void): this;
    on(event: "error", listener: (error: Error) => void): this;
  }

  function extract(): Extract;

  const tar: {
    extract: typeof extract;
  };

  export default tar;
}
