// Synthetic composer only: no Paseo modules, network, microphone or stored drafts.
import React, {useState, useRef} from 'react';
import {AppRegistry, View, Text, TextInput, Pressable} from 'react-native';
const labels = ['Send message', 'Queue message', 'Send and interrupt', 'Send and steer'];
function App() {
  const [draft, setDraft] = useState('');
  const [count, setCount] = useState(0);
  const [sent, setSent] = useState('');
  const [moved, setMoved] = useState(false);
  const [label, setLabel] = useState(0);
  const [disabled, setDisabled] = useState(false);
  const [duplicate, setDuplicate] = useState(false);
  const input = useRef(null);
  function control(name, action) {
    return <Pressable accessibilityLabel={name} accessibilityRole="button" onPress={action}>
      <Text>{name}</Text>
    </Pressable>;
  }
  function send() {
    setSent(draft); setCount(value => value + 1); setDraft(''); input.current?.clear();
  }
  function primary(key) {
    return <Pressable key={key} collapsable={false} accessibilityLabel={labels[label]}
      accessibilityRole="button" disabled={disabled} onPress={send}
      style={{height: 28, width: 28, borderRadius: 14, backgroundColor: 'blue', alignItems: 'center', justifyContent: 'center'}}>
      <Text style={{color: 'white'}}>↑</Text>
    </Pressable>;
  }
  return <View style={{flex: 1, padding: 12, paddingTop: 100, backgroundColor: 'white'}}>
    <View style={{flexDirection: 'row', gap: 10}}>
      {control('Move Send', () => setMoved(value => !value))}
      {control('Next label', () => setLabel(value => (value + 1) % labels.length))}
      {control('Reset draft', () => {setDraft(''); input.current?.clear();})}
    </View>
    <View style={{flexDirection: 'row', gap: 10}}>
      {control('Disable Send', () => setDisabled(value => !value))}
      {control('Duplicate Send', () => setDuplicate(value => !value))}
    </View>
    <Text accessibilityLabel="Fixture result">{`Mock submissions: ${count}; ${sent}`}</Text>
    {/* Paseo input.tsx exposes this testID on its onLayout/ref composer root. */}
    <View testID="message-input-root" collapsable={false} onLayout={() => {}}>
      <View style={{flexDirection: 'column', gap: 12, padding: 12, borderWidth: 1, borderRadius: 16}}>
        <TextInput ref={input} accessibilityLabel="Message agent..." placeholder="Message agent..."
          multiline defaultValue={draft} onChangeText={setDraft} autoFocus
          style={{width: '100%', color: 'black', fontSize: 16}} />
        <View style={{flexDirection: 'row', justifyContent: moved ? 'flex-start' : 'flex-end', marginHorizontal: -6}}>
          {draft.length > 0 && primary('primary')}
          {draft.length > 0 && duplicate && primary('duplicate')}
        </View>
      </View>
    </View>
  </View>;
}
AppRegistry.registerComponent('main', () => App);
