<?php

// Test file to verify PHP class usage patterns

class BaseClass
{
    public static function staticMethod(): string
    {
        return "static";
    }

    public function instanceMethod(): string
    {
        return "instance";
    }
}

class ExtendedClass extends BaseClass
{
    private BaseClass $field;

    public function __construct(BaseClass $param)
    {
        // Constructor call
        $this->field = new BaseClass();

        // Static access
        BaseClass::staticMethod();
    }

    // Method with type hints
    public function processBase(BaseClass $input): BaseClass
    {
        return $input;
    }

    // Return type hint
    public function getInstance(): BaseClass
    {
        return $this->field;
    }
}

class UsageExample
{
    // Variable with type hint
    private BaseClass $myBase;

    public function __construct()
    {
        $this->myBase = new BaseClass();
    }
}
