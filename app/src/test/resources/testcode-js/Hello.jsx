export class JsxClass {
    render() {
        return <div className="class-jsx">Hello from JSX Class</div>;
    }
}

export const JsxArrowFnComponent = ({ name }) => {
    return (
        <section>
            <p>Hello, {name} from JSX Arrow Function!</p>
        </section>
    );
};

const LocalJsxArrowFn = () => <button>Click Me</button>;

function PlainJsxFunc() {
    return <article>Some article content</article>;
}
