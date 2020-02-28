/*
* Copyright 2015-2020 OpenCB
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.opencb.opencga.client.rest;

import org.opencb.commons.datastore.core.FacetField;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.opencga.client.config.ClientConfiguration;
import org.opencb.opencga.client.exceptions.ClientException;
import org.opencb.opencga.core.models.common.TsvAnnotationParams;
import org.opencb.opencga.core.models.job.Job;
import org.opencb.opencga.core.models.sample.Sample;
import org.opencb.opencga.core.models.sample.SampleAclUpdateParams;
import org.opencb.opencga.core.models.sample.SampleCreateParams;
import org.opencb.opencga.core.models.sample.SampleUpdateParams;
import org.opencb.opencga.core.response.RestResponse;


/*
* WARNING: AUTOGENERATED CODE
*
* This code was generated by a tool.
* Autogenerated on: 2020-02-28 18:25:46
*
* Manual changes to this file may cause unexpected behavior in your application.
* Manual changes to this file will be overwritten if the code is regenerated.
*/


/**
 * This class contains methods for the Sample webservices.
 *    Client version: 2.0.0
 *    PATH: samples
 */
public class SampleClient extends AbstractParentClient {

    public SampleClient(String token, ClientConfiguration configuration) {
        super(token, configuration);
    }

    /**
     * Update the set of permissions granted for the member.
     * @param members Comma separated list of user or group ids.
     * @param data JSON containing the parameters to update the permissions. If propagate flag is set to true, it will propagate the
     *     permissions defined to the individuals that are associated to the matching samples.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<ObjectMap> updateAcl(String members, SampleAclUpdateParams data, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.put("body", data);
        return execute("samples", members, null, null, "update", params, POST, ObjectMap.class);
    }

    /**
     * Fetch catalog sample stats.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       source: Source.
     *       creationYear: Creation year.
     *       creationMonth: Creation month (JANUARY, FEBRUARY...).
     *       creationDay: Creation day.
     *       creationDayOfWeek: Creation day of week (MONDAY, TUESDAY...).
     *       status: Status.
     *       type: Type.
     *       phenotypes: Phenotypes.
     *       release: Release.
     *       version: Version.
     *       somatic: Somatic.
     *       annotation: Annotation, e.g: key1=value(,key2=value).
     *       default: Calculate default stats.
     *       field: List of fields separated by semicolons, e.g.: studies;type. For nested fields use >>, e.g.:
     *            studies>>biotype;type;numSamples[0..10]:1.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<FacetField> aggregationStats(ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        return execute("samples", null, null, null, "aggregationStats", params, GET, FacetField.class);
    }

    /**
     * Load annotation sets from a TSV file.
     * @param variableSetId Variable set id or name.
     * @param path Path where the TSV file is located in OpenCGA or where it should be located.
     * @param data JSON containing the 'content' of the TSV file if this has not yet been registered into OpenCGA.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       parents: Flag indicating whether to create parent directories if they don't exist (only when TSV file was not previously
     *            associated).
     *       annotationSetId: Annotation set id. If not provided, variableSetId will be used.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Job> loadAnnotationSets(String variableSetId, String path, TsvAnnotationParams data, ObjectMap params)
            throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.putIfNotNull("variableSetId", variableSetId);
        params.putIfNotNull("path", path);
        params.put("body", data);
        return execute("samples", null, "annotationSets", null, "load", params, POST, Job.class);
    }

    /**
     * Create sample.
     * @param data JSON containing sample information.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       individual: DEPRECATED: It should be passed in the body.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> create(SampleCreateParams data, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.put("body", data);
        return execute("samples", null, null, null, "create", params, POST, Sample.class);
    }

    /**
     * Load samples from a ped file [EXPERIMENTAL].
     * @param file file.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       variableSet: variableSet.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> load(String file, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.putIfNotNull("file", file);
        return execute("samples", null, null, null, "load", params, GET, Sample.class);
    }

    /**
     * Sample search method.
     * @param params Map containing any of the following optional parameters.
     *       include: Fields included in the response, whole JSON path must be provided.
     *       exclude: Fields excluded in the response, whole JSON path must be provided.
     *       limit: Number of results to be returned.
     *       skip: Number of results to skip.
     *       count: Get the total number of results matching the query. Deactivated by default.
     *       includeIndividual: Include Individual object as an attribute (this replaces old lazy parameter).
     *       flattenAnnotations: Flatten the annotations?.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       name: DEPRECATED: name.
     *       source: source.
     *       type: type.
     *       somatic: somatic.
     *       individual: Individual ID or name.
     *       creationDate: Creation date. Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805.
     *       modificationDate: Modification date. Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805.
     *       deleted: Boolean to retrieve deleted samples.
     *       phenotypes: Comma separated list of phenotype ids or names.
     *       annotationsetName: DEPRECATED: Use annotation queryParam this way: annotationSet[=|==|!|!=]{annotationSetName}.
     *       variableSet: DEPRECATED: Use annotation queryParam this way: variableSet[=|==|!|!=]{variableSetId}.
     *       annotation: Annotation, e.g: key1=value(,key2=value).
     *       acl: Filter entries for which a user has the provided permissions. Format: acl={user}:{permissions}. Example:
     *            acl=john:WRITE,WRITE_ANNOTATIONS will return all entries for which user john has both WRITE and WRITE_ANNOTATIONS
     *            permissions. Only study owners or administrators can query by this field. .
     *       attributes: Text attributes (Format: sex=male,age>20 ...).
     *       nattributes: Numerical attributes (Format: sex=male,age>20 ...).
     *       release: Release value (Current release from the moment the samples were first created).
     *       snapshot: Snapshot value (Latest version of samples in the specified release).
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> search(ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        return execute("samples", null, null, null, "search", params, GET, Sample.class);
    }

    /**
     * Returns the acl of the samples. If member is provided, it will only return the acl for the member.
     * @param samples Comma separated list sample IDs or UUIDs up to a maximum of 100.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       member: User or group id.
     *       silent: Boolean to retrieve all possible entries that are queried for, false to raise an exception whenever one of the entries
     *            looked for cannot be shown for whichever reason.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<ObjectMap> acl(String samples, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        return execute("samples", samples, null, null, "acl", params, GET, ObjectMap.class);
    }

    /**
     * Delete samples.
     * @param samples Comma separated list sample IDs or UUIDs up to a maximum of 100.
     * @param params Map containing any of the following optional parameters.
     *       force: Force the deletion of samples even if they are associated to files, individuals or cohorts.
     *       emptyFilesAction: Action to be performed over files that were associated only to the sample to be deleted. Possible actions
     *            are NONE, TRASH, DELETE.
     *       deleteEmptyCohorts: Boolean indicating if the cohorts associated only to the sample to be deleted should be also deleted.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       samples: Comma separated list sample IDs or UUIDs up to a maximum of 100.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> delete(String samples, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        return execute("samples", samples, null, null, "delete", params, DELETE, Sample.class);
    }

    /**
     * Get sample information.
     * @param samples Comma separated list sample IDs or UUIDs up to a maximum of 100.
     * @param params Map containing any of the following optional parameters.
     *       include: Fields included in the response, whole JSON path must be provided.
     *       exclude: Fields excluded in the response, whole JSON path must be provided.
     *       includeIndividual: Include Individual object as an attribute (this replaces old lazy parameter).
     *       flattenAnnotations: Flatten the annotations?.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       version: Sample version.
     *       deleted: Boolean to retrieve deleted samples.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> info(String samples, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        return execute("samples", samples, null, null, "info", params, GET, Sample.class);
    }

    /**
     * Update some sample attributes.
     * @param samples Comma separated list sample IDs or UUIDs up to a maximum of 100.
     * @param data params.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       incVersion: Create a new version of sample.
     *       annotationSetsAction: Action to be performed if the array of annotationSets is being updated.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> update(String samples, SampleUpdateParams data, ObjectMap params) throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.put("body", data);
        return execute("samples", samples, null, null, "update", params, POST, Sample.class);
    }

    /**
     * Update annotations from an annotationSet.
     * @param sample Sample id.
     * @param annotationSet AnnotationSet id to be updated.
     * @param data Json containing the map of annotations when the action is ADD, SET or REPLACE, a json with only the key 'remove'
     *     containing the comma separated variables to be removed as a value when the action is REMOVE or a json with only the key 'reset'
     *     containing the comma separated variables that will be set to the default value when the action is RESET.
     * @param params Map containing any of the following optional parameters.
     *       study: Study [[user@]project:]study where study and project can be either the ID or UUID.
     *       annotationSet: AnnotationSet id to be updated.
     *       action: Action to be performed: ADD to add new annotations; REPLACE to replace the value of an already existing annotation;
     *            SET to set the new list of annotations removing any possible old annotations; REMOVE to remove some annotations; RESET to
     *            set some annotations to the default value configured in the corresponding variables of the VariableSet if any.
     *       incVersion: Create a new version of sample.
     * @return a RestResponse object.
     * @throws ClientException ClientException if there is any server error.
     */
    public RestResponse<Sample> updateAnnotations(String sample, String annotationSet, ObjectMap data, ObjectMap params)
            throws ClientException {
        params = params != null ? params : new ObjectMap();
        params.put("body", data);
        return execute("samples", sample, "annotationSets", annotationSet, "annotations/update", params, POST, Sample.class);
    }
}
