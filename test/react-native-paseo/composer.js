// Synthetic composer only: no Paseo modules, network, microphone or stored drafts.
import React, {useState, useRef, useEffect} from 'react';
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
  const [inputLabel, setInputLabel] = useState('Message agent...');
  const [delaySend, setDelaySend] = useState(false);
  const [readyDraft, setReadyDraft] = useState('');
  const [stops, setStops] = useState(0);
  const input = useRef(null);
  useEffect(() => {
    if (!delaySend || !draft) return;
    const timer = setTimeout(() => setReadyDraft(draft), 3000);
    return () => clearTimeout(timer);
  }, [draft, delaySend]);
  const ready = !delaySend || readyDraft === draft;
  function control(name, action) {
    return <Pressable accessibilityLabel={name} accessibilityRole="button" onPress={action}>
      <Text>{name}</Text>
    </Pressable>;
  }
  function send() {
    setSent(draft); setCount(value => value + 1); setDraft(''); input.current?.clear();
  }
  function primary(key) {
    return <Pressable key={key} collapsable={false} accessibilityLabel={ready ? labels[label] : 'Stop'}
      accessibilityRole="button" disabled={disabled} onPress={ready ? send : () => setStops(value => value + 1)}
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
      {control('Delay Send', () => {setReadyDraft(''); setDelaySend(value => !value);})}
      {control('Rename input', () => setInputLabel('Any focused draft'))}
    </View>
    <Text accessibilityLabel="Fixture result">{`Mock submissions: ${count}; ${sent}`}</Text>
    <Text accessibilityLabel="Stop result">{`Stop activations: ${stops}`}</Text>
    {/* No application view IDs: exercise the public accessibility screen. */}
    <View collapsable={false} onLayout={() => {}}>
      <View style={{flexDirection: 'column', gap: 12, padding: 12, borderWidth: 1, borderRadius: 16}}>
        <TextInput ref={input} accessibilityLabel={inputLabel} placeholder={inputLabel}
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
