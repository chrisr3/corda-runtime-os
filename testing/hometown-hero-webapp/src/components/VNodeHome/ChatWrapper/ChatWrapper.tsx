import { useCallback, useState } from 'react';

import Chat from '../ChatComponents/Chat/Chat';
import ChatParticipants from '../ChatComponents/ChatParticipants/ChatParticipants';
import NodeDetails from '../NodeDetails/NodeDetails';
import PageHeader from '@/components/PageHeader/PageHeader';
import Section from '../Section/Section';
import style from './chatWrapper.module.scss';

const ChatWrapper = () => {
    const [selectedParticipants, setSelectedParticipants] = useState<string[]>([]);

    const handleSelectReplyParticipant = useCallback(
        (participant: string) => {
            setSelectedParticipants([participant]);
        },
        [setSelectedParticipants]
    );

    return (
        <>
            <PageHeader>V-Node Home</PageHeader>
            <div className={style.chatWrapper}>
                <Section title={'Chat'}>
                    <Chat
                        selectedParticipants={selectedParticipants}
                        handleSelectReplyParticipant={handleSelectReplyParticipant}
                    />
                </Section>
                <Section title={'Chat to'}>
                    <ChatParticipants
                        selectedParticipants={selectedParticipants}
                        setSelectedParticipants={setSelectedParticipants}
                    />
                </Section>
                <Section title={'VNode Details'}>
                    <NodeDetails />
                </Section>
            </div>
        </>
    );
};

export default ChatWrapper;
