import React, { useState } from 'react';
import { Something, AnotherThing as AT } from './another-module'; // Basic import
import * as AllThings from './all-the-things'; // Namespace import
import DefaultThing from './default-thing'; // Default import

// Test: JSX Return Type Inference (exported, uppercase) + Mutation
export function MyExportedComponent(props) {
  let counter = 0;
  if (props.update) {
    counter = counter + 1; // mutates: counter
  }
  props.wasUpdated = true; // mutates: wasUpdated (property name)
  return <div>{props.name} count: {counter}</div>;
}

// Test: JSX Return Type Inference (exported, uppercase, arrow function) + Mutation
export const MyExportedArrowComponent = ({ id }) => {
  let localStatus = "pending";
  localStatus = "processing"; // mutates: localStatus
  return <section id={id}>Status: {localStatus}</section>;
};

// Test: No JSX inference (not exported) + Mutation
function internalProcessingUtil(dataObject) {
  dataObject.isValid = false; // mutates: isValid (property name)
  return <span>{dataObject.id}</span>; // returns JSX, but not exported/uppercase
}

// Test: No JSX inference (exported, but lowercase name) + Mutation
export function updateGlobalConfig(newVal) {
  let global_config_val = 100;
  global_config_val = newVal; // mutates: global_config_val
  return global_config_val; // Does not return JSX
}

// Test: JSX inference (exported, uppercase), despite non-functional comment
export function ComponentWithComment(user /*: UserType */) {
  // This comment does not prevent JSX.Element inference for pure JS.
  return <article>Welcome, {user.name}</article>;
}

// Test: Function that mutates parameters but doesn't return JSX
export function modifyUser(user) {
  user.name = "Modified " + user.name; // mutates: name (property name)
  user.age++; // mutates: age (property name via update_expression)
}
