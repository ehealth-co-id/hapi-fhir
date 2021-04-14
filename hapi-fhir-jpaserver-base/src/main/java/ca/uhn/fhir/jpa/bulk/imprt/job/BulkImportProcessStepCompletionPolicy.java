package ca.uhn.fhir.jpa.bulk.imprt.job;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2021 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.springframework.batch.repeat.RepeatContext;
import org.springframework.batch.repeat.policy.CompletionPolicySupport;
import org.springframework.beans.factory.annotation.Value;

import static ca.uhn.fhir.jpa.bulk.imprt.job.BulkImportJobConfig.JOB_PARAM_COMMIT_INTERVAL;

public class BulkImportProcessStepCompletionPolicy extends CompletionPolicySupport {

	@Value("#{jobParameters['" + JOB_PARAM_COMMIT_INTERVAL + "']}")
	private int myChunkSize;

	@Override
	public boolean isComplete(RepeatContext context) {
		if (context.getStartedCount() < myChunkSize) {
			return false;
		}
		return true;
	}
}
