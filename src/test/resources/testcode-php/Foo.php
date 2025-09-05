<?php
namespace My\Lib;

#[Attribute1]
class Foo extends BaseFoo implements IFoo, IBar {
    private const MY_CONST = "hello";
    public static $staticProp = 123;
    protected $value;
    private ?string $nullableProp;

    #[Attribute2]
    public function __construct(int $v) {
        $this->value = $v;
    }

    /** Some doc */
    public function getValue(): int {
        return $this->value;
    }

    abstract protected function abstractMethod();
    final public static function &refReturnMethod(): array { return []; }
}

interface IFoo {}
trait MyTrait {
    public function traitMethod() {}
}
function util_func(): void {}
